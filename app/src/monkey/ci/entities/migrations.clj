(ns monkey.ci.entities.migrations
  (:require [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]            
            [monkey.ci.entities.core :as ec]
            [monkey.ci
             [protocols :as mp]
             [vault :as vault]]
            [ragtime
             [core :as rt]
             [next-jdbc :as rj]
             [protocols :as rp]]))

(defprotocol Migratable
  (->migration [obj sql-opts] "Convert the object into a migration, using sql opts"))

(defn- format-migration [sql-opts m]
  (letfn [(format-sql [stmt]
            (sql/format stmt sql-opts))]
    (-> m
        (update :up (partial mapcat format-sql))
        (update :down (partial mapcat format-sql)))))

(defrecord SqlMigration [id up down]
  Migratable
  (->migration [m opts]
    (-> (format-migration (:sql-opts opts) m)
        (rj/sql-migration))))

(defn- as-conn
  "Adds the datasource to the migration so it can be used as a database connection.
   This is necessary because the ragtime functions assume the argument is a jdbc
   connection, but most migrations need more than that (like sql formatting settings)"
  [mig {:keys [datasource]}]
  (assoc mig :ds datasource))

(defrecord FunctionMigration [id up down]
  rp/Migration
  (id [_]
    id)

  (run-up! [this db]
    (up (as-conn this db)))

  (run-down! [this db]
    (down (as-conn this db)))

  Migratable
  (->migration [m opts]
    (merge m opts)))

(defn migration
  "Creates a new migration, with given id and up/down statements.  The statements
   can either be raw strings, or honeysql statements which are then converted to sql."
  [id up down]
  (->SqlMigration id up down))

;;; Common column definitions
(def id-col [:id :integer [:not nil] :auto-increment [:primary-key]])
(def cuid-col [:cuid [:char 24] [:not nil]])
(def description-col [:description [:varchar 300]])
(def label-filters-col [:label-filters :text])
(def amount-col [:amount [:decimal 10 2] [:not nil]])

(defn cuid-idx
  "Generates a unique index on the `cuid` column for given table."
  [table]
  (h/create-index
   [:unique (keyword (str (name table) "-cuid-idx"))]
   [table :cuid]))

(defn fk-col [col]
  [col :integer [:not nil]])

(def customer-col (fk-col :customer-id))
(def repo-col (fk-col :repo-id))
(def user-col (fk-col :user-id))

(defn fk
  "Defines a foreign key constraint on the column that references another table column."
  [col ref-table ref-col & [no-cascade?]]
  (cond-> [[:foreign-key col] [:references ref-table ref-col]]
    (not no-cascade?) (conj :on-delete-cascade)))

(def fk-customer (fk :customer-id :customers :id))
(def fk-repo (fk :repo-id :repos :id))
(def fk-user (fk :user-id :users :id))

(defn col-idx
  "Defines an index for a single column"
  [table col]
  (h/create-index (keyword (str (name table) "-" (name col) "-idx"))
                  [table col]))

(defn- mig-id [idx desc]
  (format "%03d-%s" idx (name desc)))

(defn table-migration [idx table cols indices]
  (migration (mig-id idx (name table))
             (concat [(-> (h/create-table table)
                          (h/with-columns cols))]
                     indices)
             [(h/drop-table table)]))

(defn entity-table-migration [idx table extra-cols extra-indices]
  (table-migration idx table
                   (concat [id-col cuid-col] extra-cols)
                   (concat [(cuid-idx table)] extra-indices)))

(defn customer-ivs [idx]
  (->FunctionMigration
   (str idx "-create-customer-ivs")
   
   (fn [conn]
     (let [cust (ec/select conn {:select [:c.id]
                                 :from [[:customers :c]]
                                 :left-join [[:cryptos :cr] [:= :cr.customer-id :c.id]]
                                 :where [:is :cr.customer-id nil]})]
       (log/debug "Creating crypto records for" (count cust) "customers")
       (doseq [c cust]
         (ec/insert-crypto conn {:customer-id (:id c)
                                 :iv (vault/generate-iv)}))))
   ;; No rollback
   (constantly nil)))

(defn- select-params-with-iv [conn]
  (->> {:select [:pv.id :pv.value :c.iv]
           :from [[:customer-param-values :pv]]
           :join [[:customer-params :cp] [:= :cp.id :pv.params-id] 
                  [:cryptos :c] [:= :c.customer-id :cp.customer-id]]}
       (ec/select conn)))

(defn- update-single-param [conn updater pv]
  (ec/update-customer-param-value conn {:id (:id pv)
                                        :value (updater (:iv pv) (:value pv))}))

(defn- encrypt-single-param [{:keys [vault] :as conn} pv]
  (update-single-param conn (partial mp/encrypt vault) pv))

(defn- decrypt-single-param [{:keys [vault] :as conn} pv]
  (update-single-param conn (partial mp/decrypt vault) pv))

(defn encrypt-params [idx]
  (->FunctionMigration
   (str idx "-encrypt-params")
   (fn [conn]
     ;; Encrypt all customer parameter values
     (->> (select-params-with-iv conn)
          (map (partial encrypt-single-param conn))
          (doall)))
   (fn [conn]
     ;; Decrypt all customer parameter values
     (->> (select-params-with-iv conn)
          (map (partial decrypt-single-param conn))
          (doall)))))

(def migrations
  [(entity-table-migration
    1 :customers
    [[:name [:varchar 200] [:not nil]]]
    [(h/create-index [:unique :customers-name-idx] [:customers :name])])

   (entity-table-migration
    2 :repos
    [[:display-id [:varchar 50] [:not nil]]
     customer-col
     [:name [:varchar 200] [:not nil]]
     [:url [:varchar 300]]
     [:main-branch [:varchar 100]]
     [:github-id :integer]
     fk-customer]
    [(col-idx :repos :customer-id)])

   (table-migration
    3 :repo-labels
    [id-col
     repo-col
     [:name [:varchar 100]]
     [:value [:varchar 100]]
     fk-repo]
    [(col-idx :repo-labels :repo-id)])

   (entity-table-migration
    4 :customer-params
    [customer-col
     description-col
     label-filters-col
     fk-customer]
    [(col-idx :customer-params :customer-id)])

   (entity-table-migration
    5 :webhooks
    [repo-col
     [:secret [:varchar 100] [:not nil]]
     fk-repo]
    [(col-idx :webhooks :repo-id)])

   (entity-table-migration
    6 :ssh-keys
    [customer-col
     [:private-key :text]
     [:public-key :text]
     description-col
     label-filters-col
     fk-customer]
    [(col-idx :ssh-keys :customer-id)])

   (table-migration
    7 :customer-param-values
    [id-col
     (fk-col :params-id)
     [:name [:varchar 100] [:not nil]]
     [:value :mediumtext [:not nil]]
     (fk :params-id :customer-params :id)]
    [(col-idx :customer-param-values :params-id)])

   (entity-table-migration
    9 :builds
    [[:idx :integer [:not nil]]
     repo-col
     [:display-id [:varchar 50]]
     [:start-time :timestamp]
     [:end-time :timestamp]
     [:status [:varchar 30]]
     [:script-dir [:varchar 300]]
     [:git :text]
     fk-repo]
    [(col-idx :builds :repo-id)])

   (entity-table-migration
    10 :users
    [[:type [:varchar 20] [:not nil]]
     [:type-id [:varchar 100] [:not nil]]
     [:email [:varchar 100]]]
    [(h/create-index :user-type-idx [:users :type :type-id])])

   (table-migration
    11 :user-customers
    [user-col
     customer-col
     [[:primary-key :user-id :customer-id]]
     fk-user
     fk-customer]
    [(col-idx :user-customers :user-id)
     (col-idx :user-customers :customer-id)])

   (entity-table-migration
    12 :jobs
    [[:display-id [:varchar 100] [:not nil]]
     (fk-col :build-id)
     [:details :mediumtext]
     [:start-time :timestamp]
     [:end-time :timestamp]
     [:status [:varchar 20]]
     (fk :build-id :builds :id)]
    [(col-idx :jobs :build-id)
     (col-idx :jobs :display-id)])

   (entity-table-migration
    13 :join-requests
    [customer-col
     user-col
     [:status [:varchar 20] [:not nil]]
     [:request-msg [:varchar 500]]
     [:response-msg [:varchar 500]]
     fk-customer
     fk-user]
    [(col-idx :join-requests :customer-id)
     (col-idx :join-requests :user-id)])

   (table-migration
    14 :repo-indices
    [[:repo-id :integer [:not nil] [:primary-key]]
     (fk :repo-id :repos :id)]
    [])

   (migration
    (mig-id 15 :job-credit-multiplier)
    [{:alter-table :jobs
      :add-column [:credit-multiplier [:decimal 4 2] :default 0]}]
    [{:alter-table :jobs
      :drop-column :credit-multiplier}])

   (migration
    (mig-id 16 :build-credits)
    [{:alter-table :builds
      :add-column [:credits [:decimal 10 2] :default 0]}]
    [{:alter-table :builds
      :drop-column :credits}])

   (migration
    (mig-id 17 :build-source)
    [{:alter-table :builds
      :add-column [:source [:varchar 30]]}]
    [{:alter-table :builds
      :drop-column :source}])

   (migration
    (mig-id 18 :build-message)
    [{:alter-table :builds
      :add-column [:message :text]}]
    [{:alter-table :builds
      :drop-column :message}])

   (entity-table-migration
    19 :email-registrations
    [[:email [:varchar 200]]]
    [])

   (migration
    (mig-id 20 :email-reg-uniqueness)
    [(h/create-index [:unique :email-reg-idx] [:email-registrations :email])]
    [(h/drop-index :email-reg-idx)])

   (entity-table-migration
    21 :credit-subscriptions
    [customer-col
     amount-col
     [:valid-from :timestamp]
     [:valid-until :timestamp]
     fk-customer]
    [(col-idx :credit-subscriptions :customer-id)])

   (entity-table-migration
    22 :customer-credits
    [customer-col
     amount-col
     [:from-time :timestamp]
     [:type [:varchar 20]]
     [:user-id :integer]                ; can be nil
     [:subscription-id :integer]
     [:reason [:varchar 300]]
     fk-customer
     fk-user
     (fk :subscription-id :credit-subscriptions :id)]
    [(col-idx :customer-credits :customer-id)
     (col-idx :customer-credits :user-id)
     (col-idx :customer-credits :subscription-id)])

   (entity-table-migration
    23 :credit-consumptions
    [amount-col
     [:consumed-at :timestamp]
     [:credit-id :integer [:not nil]]
     [:build-id :integer [:not nil]]
     (fk :credit-id :customer-credits :id)
     (fk :build-id :builds :id)]
    [(col-idx :credit-consumptions :credit-id)
     (col-idx :credit-consumptions :build-id)])

   (entity-table-migration
    ;; Caching table that holds the current available credits value for each customer
    24 :available-credits
    [customer-col
     amount-col
     fk-customer]
    [(col-idx :available-credits :customer-id)])

   (entity-table-migration
    25 :bb-webhooks
    [[:webhook-id :integer [:not nil]]
     [:bitbucket-id [:varchar 100] [:not nil]]
     [:workspace [:varchar 300] [:not nil]]
     [:repo-slug [:varchar 100] [:not nil]]
     (fk :webhook-id :webhooks :id)]
    [(col-idx :bb-webhooks :webhook-id)])

   (table-migration
    26 :cryptos
    ;; Only one record per customer, so make customer id the pk
    [[:customer-id :integer [:not nil] [:primary-key]]
     [:iv [:binary 16]]
     fk-customer]
    [])

   (customer-ivs 27)
   (encrypt-params 28)])

(defn prepare-migrations
  "Prepares all migrations by formatting to sql, creates a ragtime migration object from it."
  ([migrations opts]
   (map #(->migration % opts) migrations))
  ([opts]
   (prepare-migrations migrations opts)))

(defn- load-migrations [{:keys [ds] :as opts}]
  (let [db (rj/sql-database ds)
        mig (prepare-migrations (dissoc opts :ds))
        idx (rt/into-index mig)]
    [db mig idx]))

(defn- load-and-run-migrations [conn]
  (let [[db mig idx :as r] (load-migrations conn)]
    (log/info "Applying" (count mig) "migrations")
    (rt/migrate-all db idx mig)
    r))

(defn run-migrations!
  "Runs SQL migrations on the database indicated by the connection"
  [conn]
  (load-and-run-migrations conn))

(defn with-migrations
  "Runs migrations, executes `f` and then rolls back.  Useful for testing."
  [conn f]
  (let [[db mig idx] (load-and-run-migrations conn)]
    (try
      (f)
      (finally
        (rt/rollback-last db idx (count mig))))))
