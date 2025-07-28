(ns monkey.ci.entities.migrations
  (:require [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]            
            [monkey.ci.entities
             [build :as eb]
             [core :as ec]]
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
(def org-col (fk-col :org-id))
(def repo-col (fk-col :repo-id))
(def user-col (fk-col :user-id))
(def job-col (fk-col :job-id))

(defn fk
  "Defines a foreign key constraint on the column that references another table column."
  [col ref-table ref-col & [no-cascade?]]
  (cond-> [[:foreign-key col] [:references ref-table ref-col]]
    (not no-cascade?) (conj :on-delete-cascade)))

(def fk-customer (fk :customer-id :customers :id))
(def fk-org (fk :org-id :customers :id))
(def fk-repo (fk :repo-id :repos :id))
(def fk-user (fk :user-id :users :id))
(def fk-job (fk :job-id :jobs :id))

(defn- idx-name [table col]
  (keyword (str (name table) "-" (name col) "-idx")))

(defn col-idx
  "Defines an index for a single column"
  [table col]
  (h/create-index (idx-name table col)
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
  (ec/update-entity conn
                    :customer-param-values
                    {:id (:id pv)
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

(defn- select-ssh-keys-with-iv [conn]
  (->> {:select [:k.id :k.private-key :c.iv]
        :from [[:ssh-keys :k]]
        :join [[:cryptos :c] [:= :c.customer-id :k.customer-id]]}
       (ec/select conn)))

(defn- update-single-ssh-key [conn updater k]
  (ec/update-ssh-key conn {:id (:id k)
                           :private-key (updater (:iv k) (:private-key k))}))

(defn- encrypt-single-ssh-key [{:keys [vault] :as conn} k]
  (update-single-ssh-key conn (partial mp/encrypt vault) k))

(defn- decrypt-single-ssh-key [{:keys [vault] :as conn} k]
  (update-single-ssh-key conn (partial mp/decrypt vault) k))

(defn encrypt-ssh-keys [idx]
  (->FunctionMigration
   (str idx "-encrypt-ssh-keys")
   (fn [conn]
     ;; Encrypt all private ssh keys
     (->> (select-ssh-keys-with-iv conn)
          (map (partial encrypt-single-ssh-key conn))
          (doall)))
   (fn [conn]
     (->> (select-ssh-keys-with-iv conn)
          (map (partial decrypt-single-ssh-key conn))
          (doall)))))

(defn calc-next-idx
  "For all repos, calculates next build idx and stores it in the `repo-indices` table."
  [idx]
  (->FunctionMigration
   (str idx "-calc-next-idx")
   (fn [conn]
     (letfn [(insert-repo-idx [{:keys [repo-id last-idx]}]
               (ec/insert-repo-idx conn {:repo-id repo-id
                                         :next-idx (inc last-idx)}))]
       (->> (ec/select conn {:select [:b.repo-id [:%max.idx :last-idx]]
                             :from [[:builds :b]]
                             :group-by [:b.repo-id]})
            (map insert-repo-idx)
            (doall))))
   (fn [conn]
     ;; Delete them all
     (ec/delete-repo-indices conn [:> :next-idx 0]))))

(defn generate-org-deks
  "Generates a data encryption key (DEK) for each org that does not yet have one."
  [idx]
  (->FunctionMigration
   (str idx "-generate-org-deks")
   (fn [conn]
     (let [dg (get-in conn [:crypto :dek-generator])]
       ;; Read all org and crypto records, generate DEK and write back
       (->> (ec/select-cryptos conn [:is :dek nil])
            (map (fn [o]
                   (assoc o :dek (:enc (dg (:org-id o))))))
            (map (fn [o]
                   (ec/update-crypto conn o)))
            (doall))))
   (fn [conn]
     ;; Noop
     )))

(defn- re-encrypt-value-mig [id query prop updater]
  (->FunctionMigration
   id
   (fn [{:keys [vault] :as conn}]
     (let [e (get-in conn [:crypto :encrypter])]
       (letfn [(re-encrypt [obj]
                 ;; Decrypt using vault, then re-encrypt using the org id as nonce
                 (assoc obj prop (-> (prop obj)
                                     (as-> ev (mp/decrypt vault (:iv obj) ev))
                                     (e (:org-cuid obj) (:cuid obj)))))]
         (->> (ec/select conn query)
              (map re-encrypt)
              (map (partial updater conn))
              (doall)))))
   
   (fn [{:keys [vault] :as conn}]
     (let [d (get-in conn [:crypto :decrypter])]
       (letfn [(re-encrypt [obj]
                 ;; Decrypt using vault, then re-encrypt using the org id as nonce
                 (assoc obj prop (-> (prop obj)
                                     (d (:org-cuid obj) (:cuid obj))
                                     (as-> dv (mp/encrypt vault (:iv obj) dv)))))]
         (->> (ec/select conn query)
              (map re-encrypt)
              (map (partial updater conn))
              (doall)))))))

(defn re-encrypt-params
  "Reads and decrypts all params that do not yet use the organization DEK, and
   re-encrypts them."
  [idx]
  (re-encrypt-value-mig
   (str idx "-re-encrypt-params")
   {:select [:pv.id :op.cuid :pv.params-id :pv.value :c.org-id [:o.cuid :org-cuid] :c.dek :c.iv]
    :from [[:org-param-values :pv]]
    :join [[:org-params :op] [:= :op.id :pv.params-id]
           [:orgs :o] [:= :o.id :op.org-id]
           [:cryptos :c] [:= :c.org-id :op.org-id]]}
   :value
   (fn [conn pv]
     (ec/update-org-param-value conn (select-keys pv [:id :value])))))

(defn re-encrypt-ssh-keys
  "Reads and decrypts all private ssh keys that do not yet use the organization DEK, and
   re-encrypts them."
  [idx]
  (re-encrypt-value-mig
   (str idx "-re-encrypt-ssh-keys")
   {:select [:sk.id :sk.cuid :sk.org-id :sk.private-key :c.dek :c.iv [:o.cuid :org-cuid]]
    :from [[:ssh-keys :sk]]
    :join [[:cryptos :c] [:= :c.org-id :sk.org-id]
           [:orgs :o] [:= :o.id :sk.org-id]]}
   :private-key
   (fn [conn k]
     (ec/update-ssh-key conn (select-keys k [:id :private-key])))))

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
    ;; Holds the next available build index per repo
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
     [:user-id :integer]             ; can be nil if subscription type
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
   (encrypt-params 28)
   (encrypt-ssh-keys 29)

   (table-migration
    30 :sysadmins
    [user-col
     [:password [:varchar 100] [:not nil]]
     fk-user]
    [(col-idx :sysadmins :user-id)])

   (migration
    (mig-id 31 :build-idx-idx)
    [(col-idx :builds :idx)]
    [(h/drop-index (idx-name :builds :idx))])

   (entity-table-migration
    32 :invoices
    [customer-col
     [:kind [:varchar 30] [:not nil]]
     [:invoice-nr [:varchar 50] [:not nil]]
     [:date :date [:not nil]]
     [:net-amount [:decimal 10 2] [:not nil]]
     [:vat-perc [:decimal 5 2] [:not nil]]
     [:currency [:varchar 30] [:not nil]]
     [:details :text]
     fk-customer]
    [(col-idx :invoices :customer-id)])

   (table-migration
    33 :build-runner-details
    ;; Contains additional information about the runner for a build
    [[:build-id :integer [:not nil] [:primary-key]]
     [:runner [:varchar 50] [:not nil]]
     [:details :text]
     (fk :build-id :builds :id)]
    [(col-idx :build-runner-details :build-id)])

   (migration
    (mig-id 34 :repo-indices-next-idx)
    [{:alter-table :repo-indices
      :add-column [:next-idx :integer [:not nil]]}]
    [{:alter-table :repo-indices
      :drop-column :next-idx}])

   (calc-next-idx 35)

   (entity-table-migration
    36 :queued-tasks
    [[:task :text]
     [:creation-time :timestamp]]
    [])

   (migration
    (mig-id 37 :rename-table-cust-to-org)
    [{:alter-table :customers
      :rename-table :orgs}]
    [{:alter-table :orgs
      :rename-table :customers}])

   (let [tables [:repos
                 :customer-params
                 :ssh-keys
                 :user-customers
                 :join-requests
                 :credit-subscriptions
                 :customer-credits
                 :invoices
                 :available-credits
                 :cryptos]]
     (migration
      (mig-id 38 :rename-col-cust-to-org)
      (->> tables
           (map (fn [t]
                  {:alter-table t
                   :rename-column [:customer-id :org-id]})))
      (->> tables
           (map (fn [t]
                  {:alter-table t
                   :rename-column [:org-id :customer-id]})))))

   (migration
    (mig-id 39 :rename-table-cust-credits-to-org-credits)
    [{:alter-table :customer-credits
      :rename-table :org-credits}]
    [{:alter-table :org-credits
      :rename-table :customer-credits}])

   (migration
    (mig-id 40 :rename-table-cust-params-to-org-params)
    [{:alter-table :customer-params
      :rename-table :org-params}]
    [{:alter-table :org-params
      :rename-table :customer-params}])

   (migration
    (mig-id 41 :rename-table-cust-param-values-to-org-param-values)
    [{:alter-table :customer-param-values
      :rename-table :org-param-values}]
    [{:alter-table :org-param-values
      :rename-table :customer-param-values}])

   (migration
    (mig-id 42 :rename-table-user-custs-to-user-orgs)
    [{:alter-table :user-customers
      :rename-table :user-orgs}]
    [{:alter-table :user-orgs
      :rename-table :user-customers}])

   (table-migration
    43 :job-events
    [id-col
     job-col
     [:event [:varchar 30] [:not nil]]
     [:time :timestamp]
     [:details :mediumtext]
     fk-job]
    [(col-idx :job-events :job-id)])

   (migration
    (mig-id 44 :add-crypto-dek)
    [{:alter-table :cryptos
      :add-column [:dek [:varchar 100]]}]
    [{:alter-table :cryptos
      :drop-column :dek}])

   (generate-org-deks 45)
   (re-encrypt-params 46)
   (re-encrypt-ssh-keys 47)

   (migration
    (mig-id 48 :add-webhook-timestamps)
    ;; h2 only allows adding or dropping one column at a time
    [{:alter-table :webhooks
      :add-column [:creation-time :timestamp]}
     {:alter-table :webhooks
      :add-column [:last-inv-time :timestamp]}]
    [{:alter-table :webhooks
      :drop-column :creation-time}
     {:alter-table :webhooks
      :drop-column :last-inv-time}])])

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
    (log/info "Applying" (count mig) "migrations with db" db)
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
