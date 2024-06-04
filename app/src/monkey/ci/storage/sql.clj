(ns monkey.ci.storage.sql
  "Storage implementation that uses an SQL database for persistence.  This namespace provides
   a layer on top of the entities namespace to perform the required queries whenever a 
   document is saved or loaded."
  (:require [clojure.set :as cset]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci.entities
             [build :as eb]
             [core :as ec]
             [customer :as ecu]
             [migrations :as emig]
             [param :as eparam]
             [repo :as er]
             [ssh-key :as essh]
             [user :as eu]
             [webhook :as ewh]]
            [monkey.ci
             [labels :as lbl]
             [protocols :as p]
             [sid :as sid]
             [spec :as spec]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.spec.db-entities]
            [monkey.ci.spec.entities]
            [next.jdbc.connection :as conn])
  (:import com.zaxxer.hikari.HikariDataSource))

(def deleted? (fnil pos? 0))

(defn- drop-nil [m]
  (mc/filter-vals some? m))

(defn- db->labels [labels]
  (map #(select-keys % [:name :value]) labels))

(defn- repo->db
  "Converts the repository into an entity that can be sent to the database."
  [r cust-id]
  (-> r
      (select-keys [:name :url :main-branch :github-id])
      (dissoc :id)
      (assoc :display-id (:id r)
             :customer-id cust-id)))

(defn- db->repo
  "Converts the repo entity (a db record) into a repository.  If `f` is provided,
   it is invoked to allow some processing on the resulting object."
  [re & [f]]
  (cond->
      (-> re
          (dissoc :cuid :customer-id :display-id)
          (assoc :id (:display-id re))
          (mc/update-existing :labels db->labels)
          (drop-nil))
      f (f re)))

(defn- insert-repo-labels [conn labels re]
  (when-not (empty? labels)
    (->> labels
         (map #(assoc % :repo-id (:id re)))
         (ec/insert-repo-labels conn))))

(defn- update-repo-labels [conn labels]
  (doseq [l labels]
    (ec/update-repo-label conn l)))

(defn- delete-repo-labels [conn labels]
  (when-not (empty? labels)
    (ec/delete-repo-labels conn [:in :id (map :id labels)])))

(defn- sync-repo-labels [conn labels re]
  {:pre [(some? (:id re))]}
  (let [ex (ec/select-repo-labels conn (ec/by-repo (:id re)))
        {:keys [insert update delete] :as r} (lbl/reconcile-labels ex labels)]
    (log/debug "Reconciled labels" labels "into" r)
    (insert-repo-labels conn insert re)
    (update-repo-labels conn update)
    (delete-repo-labels conn delete)))

(defn- insert-repo [conn re repo]
  (let [re (ec/insert-repo conn re)]
    (insert-repo-labels conn (:labels repo) re)))

(defn- update-repo [conn re repo existing]
  (when (not= re existing)
    (let [re (merge existing re)]
      (ec/update-repo conn re)
      (sync-repo-labels conn (:labels repo) re))))

(defn- upsert-repo [conn repo cust-id]
  (spec/valid? :entity/repo repo)
  (let [re (repo->db repo cust-id)]
    (spec/valid? :db/repo re)
    (if-let [existing (ec/select-repo conn [:and
                                            (ec/by-customer cust-id)
                                            (ec/by-display-id (:id repo))])]
      (update-repo conn re repo existing)
      (insert-repo conn re repo))))

(defn- upsert-repos [conn {:keys [repos]} cust-id]
  (doseq [[id r] repos]
    (upsert-repo conn r cust-id)))

(defn- cust->db [cust]
  (-> cust
      (select-keys [:name])
      (assoc :cuid (:id cust))))

(defn- insert-customer [conn cust]
  (let [cust-id (:id (ec/insert-customer conn (cust->db cust)))]
    (upsert-repos conn cust cust-id)
    cust))

(defn- update-customer [conn cust existing]
  (let [ce (cust->db cust)]
    (spec/valid? :db/customer ce)
    (when (not= ce existing)
      (ec/update-customer conn (merge existing ce)))
    (upsert-repos conn cust (:id existing))
    cust))

(defn- upsert-customer [conn cust]
  (spec/valid? :entity/customer cust)
  (if-let [existing (ec/select-customer conn (ec/by-cuid (:id cust)))]
    (update-customer conn cust existing)
    (insert-customer conn cust)))

(defn- select-customer [conn cuid]
  (letfn [(entities->repos [repos]
            (reduce-kv (fn [r _ v]
                         (assoc r (:display-id v) (db->repo v)))
                       {}
                       repos))
          (db->cust [c]
            (-> c
                (dissoc :cuid)
                (assoc :id (str (:cuid c)))
                (update :repos entities->repos)))]
    (when cuid
      (some-> (ecu/customer-with-repos conn (ec/by-cuid cuid))
              (db->cust)))))

(defn- customer-exists? [conn cuid]
  (some? (ec/select-customer conn (ec/by-cuid cuid))))

(defn- delete-customer [conn cuid]
  (when cuid
    (ec/delete-customers conn (ec/by-cuid cuid))))

(defn- global-sid? [type sid]
  (= [st/global (name type)] (take 2 sid)))

(def customer? (partial global-sid? :customers))
(def webhook? (partial global-sid? :webhooks))

(defn- global-sid->cuid [sid]
  (nth sid 2))

(defn- insert-webhook [conn wh]
  (if-let [cust (ec/select-customer conn (ec/by-cuid (:customer-id wh)))]
    (if-let [repo (ec/select-repo conn [:and
                                        (ec/by-display-id (:repo-id wh))
                                        (ec/by-customer (:id cust))])]
      (let [we {:cuid (:id wh)
                :repo-id (:id repo)
                :secret (:secret-key wh)}]
        (ec/insert-webhook conn we))
      (throw (ex-info "Repository does not exist" wh)))
    (throw (ex-info "Customer does not exist" wh))))

(defn- update-webhook [conn wh existing])

(defn- upsert-webhook [conn wh]
  (spec/valid? :entity/webhook wh)
  (if-let [existing (ec/select-webhook conn (ec/by-cuid (:id wh)))]
    (update-webhook conn wh existing)
    (insert-webhook conn wh)))

(defn- select-webhook [conn cuid]
  (-> (ewh/select-webhook-as-entity conn cuid)
      (first)
      (update :id str)
      (update :customer-id str)))

(defn- top-sid? [type sid]
  (and (= 2 (count sid))
       (= (name type) (first sid))))

(def ssh-key? (partial top-sid? :ssh-keys))

(defn- ssh-key->db [k]
  (-> k
      (assoc :cuid (:id k))
      (dissoc :id :customer-id)))

(defn- insert-ssh-key [conn ssh-key]
  (log/debug "Inserting ssh key:" ssh-key)
  (if-let [cust (ec/select-customer conn (ec/by-cuid (:customer-id ssh-key)))]
    (ec/insert-ssh-key conn (-> ssh-key
                                (ssh-key->db)
                                (assoc :customer-id (:id cust))))
    (throw (ex-info "Customer not found when inserting ssh key" ssh-key))))

(defn- update-ssh-key [conn ssh-key existing]
  (log/debug "Updating ssh key:" ssh-key)
  (ec/update-ssh-key conn (merge existing (ssh-key->db ssh-key))))

(defn- upsert-ssh-key [conn ssh-key]
  (spec/valid? :entity/ssh-key ssh-key)
  (if-let [existing (ec/select-ssh-key conn (ec/by-cuid (:id ssh-key)))]
    (update-ssh-key conn ssh-key existing)
    (insert-ssh-key conn ssh-key)))

(defn- upsert-ssh-keys [conn ssh-keys]
  (doseq [k ssh-keys]
    (upsert-ssh-key conn k))
  ssh-keys)

(defn- select-ssh-keys [conn customer-id]
  (essh/select-ssh-keys-as-entity conn customer-id))

(def params? (partial top-sid? :build-params))

(defn- insert-param-values [conn values param-id]
  (when-not (empty? values)
    (->> values
         (map (fn [v]
                (-> (select-keys v [:name :value])
                    (assoc :params-id param-id))))
         (ec/insert-customer-param-values conn))))

(defn- update-param-values [conn values]
  (doseq [pv values]
    (ec/update-customer-param-value conn pv)))

(defn- delete-param-values [conn values]
  (ec/delete-customer-param-values conn [:in :id (map :id values)]))

(defn- param->db [param cust-id]
  (-> param
      (select-keys [:description :label-filters])
      (assoc :customer-id cust-id
             :cuid (:id param))))

(defn- insert-param [conn param cust-id]
  (let [{:keys [id]} (ec/insert-customer-param conn (param->db param cust-id))]
    (insert-param-values conn (:parameters param) id)))

(defn- update-param [conn param cust-id existing]
  (ec/update-customer-param conn (merge existing (param->db param cust-id)))
  (let [ex-vals (ec/select-customer-param-values conn (ec/by-params (:id existing)))
        r (lbl/reconcile-labels ex-vals (:parameters param))]
    (log/debug "Reconciled param values:" r)
    (insert-param-values conn (:insert r) (:id existing))
    (update-param-values conn (:update r))
    (delete-param-values conn (:delete r))))

(defn- upsert-param [conn param cust-id]
  (spec/valid? :entity/customer-params param)
  (if-let [existing (ec/select-customer-param conn (ec/by-cuid (:id param)))]
    (update-param conn param cust-id existing)
    (insert-param conn param cust-id)))

(defn- upsert-params [conn params]
  (when-not (empty? params)
    (let [{cust-id :id} (ec/select-customer conn (ec/by-cuid (:customer-id (first params))))]
      (doseq [p params]
        (upsert-param conn p cust-id))
      params)))

(defn- select-params [conn customer-id]
  ;; Select customer params and values for customer cuid
  (eparam/select-customer-params-with-values conn customer-id))

(defn user? [sid]
  (and (= 4 (count sid))
       (= [st/global "users"] (take 2 sid))))

(defn- user->db [user]
  (-> (select-keys user [:type :type-id :email])
      (assoc :cuid (:id user))
      (mc/update-existing :type name)
      (mc/update-existing :type-id str)))

(defn- db->user [user]
  (-> (select-keys user [:type :type-id :email])
      (mc/update-existing :type keyword)
      (assoc :id (:cuid user))))

(defn- insert-user [conn user]
  (let [{:keys [id] :as ins} (ec/insert-user conn (user->db user))
        ids (ecu/customer-ids-by-cuids conn (:customers user))]
    (ec/insert-user-customers conn id ids)
    ins))

(defn- update-user [conn user {user-id :id :as existing}]
  (when (ec/update-user conn (merge existing (user->db user)))
    ;; Update user/customer links
    (let [existing-cust (set (eu/select-user-customer-cuids conn user-id))
          new-cust (set (:customers user))
          to-add (cset/difference new-cust existing-cust)
          to-remove (cset/difference existing-cust new-cust)]
      (ec/insert-user-customers conn user-id (ecu/customer-ids-by-cuids conn to-add))
      (when-not (empty? to-remove)
        (ec/delete-user-customers conn [:in :customer-id (ecu/customer-ids-by-cuids conn to-remove)]))
      user)))

(defn- upsert-user [conn user]
  (let [existing (ec/select-user conn (ec/by-cuid (:id user)))]
    (update-user conn user existing)
    (insert-user conn user)))

(defn- select-user [conn [type type-id]]
  (when-let [r (ec/select-user conn [:and
                                     [:= :type type]
                                     [:= :type-id type-id]])]
    (let [cust (eu/select-user-customer-cuids conn (:id r))]
      (cond-> (db->user r)
        true (drop-nil)
        (not-empty cust) (assoc :customers cust)))))

(defn build? [sid]
  (and (= "builds" (first sid))
       (= 4 (count sid))))

(defn build-repo? [sid]
  (and (= "builds" (first sid))
       (= 3 (count sid))))

(defn- build->db [build]
  (-> build
      (select-keys [:status :start-time :end-time :idx])
      (mc/update-existing :status name)
      (assoc :display-id (:build-id build))))

(defn- db->build [build]
  (-> build
      (select-keys [:status :start-time :end-time :idx])
      (mc/update-existing :status keyword)
      (ec/start-time->int)
      (ec/end-time->int)
      (assoc :build-id (:display-id build))))

(defn- job->db [job]
  (-> job
      (select-keys [:status :start-time :end-time])
      (mc/update-existing :status name)
      (assoc :display-id (:id job)
             :details (dissoc job :id :status :start-time :end-time))))

(defn- db->job [job]
  (-> job
      (select-keys [:status :start-time :end-time])
      (merge (:details job))
      (mc/update-existing :status keyword)
      (assoc :id (:display-id job))
      (drop-nil)))

(defn- insert-jobs [conn jobs build-id]
  (doseq [j jobs]
    (ec/insert-job conn (assoc (job->db j) :build-id build-id))))

(defn- update-jobs [conn jobs]
  (doseq [[upd ex] jobs]
    (let [upd (merge ex (job->db upd))]
      (ec/update-job conn upd))))

(defn- insert-build [conn build]
  (when-let [repo-id (er/repo-for-build-sid conn (:customer-id build) (:repo-id build))]
    (let [{:keys [id] :as ins} (ec/insert-build conn (-> (build->db build)
                                                         (assoc :repo-id repo-id)))]
      (insert-jobs conn (vals (:jobs build)) id)
      ins)))

(defn- update-build [conn {:keys [jobs] :as build} existing]
  (ec/update-build conn (merge existing (build->db build)))
  (let [ex-jobs (ec/select-jobs conn (ec/by-build (:id existing)))
        new-ids (set (keys jobs))
        existing-ids (set (map :display-id ex-jobs))
        to-delete (cset/difference existing-ids new-ids)
        to-insert (apply dissoc jobs existing-ids)
        to-update (reduce (fn [r ej]
                            (let [n (get jobs (:display-id ej))]
                              (cond-> r
                                ;; TODO Only update modified jobs
                                n (conj [n ej]))))
                          []
                          ex-jobs)]
    (when-not (empty? to-delete)
      ;; Delete all removed jobs (although this is a situation that probably never happens)
      (ec/delete-jobs conn [:and
                            [:= :build-id (:id existing)]
                            [:in :display-id to-delete]]))
    (when-not (empty? to-update)
      (update-jobs conn to-update))
    (when-not (empty? to-insert)
      ;; Insert new jobs
      (insert-jobs conn (vals to-insert) (:id existing)))
    build))

(defn- upsert-build [conn build]
  ;; Fetch build by customer cuild and repo and build display ids
  (if-let [existing (eb/select-build-by-sid conn (:customer-id build) (:repo-id build) (:build-id build))]
    (update-build conn build existing)
    (insert-build conn build)))

(defn- select-jobs [conn build-id]
  (->> (ec/select-jobs conn (ec/by-build build-id))
       (map db->job)
       (map (fn [j] [(:id j) j]))
       (into {})))

(defn- select-build [conn [cust-id repo-id build-id :as sid]]
  (when-let [build (apply eb/select-build-by-sid conn sid)]
    (-> (db->build build)
        (assoc :customer-id cust-id
               :repo-id repo-id
               :jobs (select-jobs conn (:id build)))
        (drop-nil))))

(defn- select-repo-builds [conn sid]
  (apply eb/select-build-ids-for-repo conn sid))

(defrecord SqlStorage [conn]
  p/Storage
  (read-obj [_ sid]
    (cond
      (customer? sid)
      (select-customer conn (global-sid->cuid sid))
      (user? sid)
      (select-user conn (drop 2 sid))
      (build? sid)
      (select-build conn (rest sid))
      (webhook? sid)
      (select-webhook conn (global-sid->cuid sid))
      (ssh-key? sid)
      (select-ssh-keys conn (second sid))
      (params? sid)
      (select-params conn (second sid))))
  
  (write-obj [_ sid obj]
    (when (cond
            (customer? sid)
            (upsert-customer conn obj)
            (user? sid)
            (upsert-user conn obj)
            (build? sid)
            (upsert-build conn obj)
            (webhook? sid)
            (upsert-webhook conn obj)
            (ssh-key? sid)
            (upsert-ssh-keys conn obj)
            (params? sid)
            (upsert-params conn obj)
            :else
            (log/warn "Unrecognized sid when writing:" sid))
      sid))

  (obj-exists? [_ sid]
    (when (customer? sid)
      (customer-exists? conn (global-sid->cuid sid))))

  (delete-obj [_ sid]
    (deleted?
     ;; TODO Allow deleting other entities
     (when (customer? sid)
       (delete-customer conn (global-sid->cuid sid)))))

  (list-obj [_ sid]
    (when (build-repo? sid)
      (select-repo-builds conn (rest sid))))

  co/Lifecycle
  (start [this]
    (log/debug "Starting DB connection")
    (emig/run-migrations! (:ds conn))
    this)

  (stop [this]
    (when-let [ds (:ds conn)]
      (log/debug "Closing DB connection")
      (.close ds))
    this))

(defn select-watched-github-repos [{:keys [conn]} github-id]
  (let [matches (ec/select-repos conn [:= :github-id github-id])
        ;; Select all customer records for the repos
        customers (->> matches
                       (map :customer-id)
                       (distinct)
                       (vector :in :id)
                       (ec/select-customers conn)
                       (group-by :id)
                       (mc/map-vals first))
        add-cust-cuid (fn [r e]
                        (assoc r :customer-id (str (get-in customers [(:customer-id e) :cuid]))))
        convert (fn [e]
                  (db->repo e add-cust-cuid))]
    (map convert matches)))

(defn watch-github-repo [{:keys [conn]} {:keys [customer-id] :as repo}]
  (when-let [cust (ec/select-customer conn (ec/by-cuid customer-id))]
    (let [r (ec/insert-repo conn (repo->db repo (:id cust)))]
      (sid/->sid [customer-id (:display-id r)]))))

(defn unwatch-github-repo [{:keys [conn]} [customer-id repo-id]]
  ;; TODO Use a single query with join
  (some? (when-let [cust (ec/select-customer conn (ec/by-cuid customer-id))]
           (when-let [repo (ec/select-repo conn [:and
                                                 [:= :customer-id (:id cust)]
                                                 [:= :display-id repo-id]])]
             (ec/update-repo conn (assoc repo :github-id nil))))))

(def overrides
  {:watched-github-repos
   {:find select-watched-github-repos
    :watch watch-github-repo
    :unwatch unwatch-github-repo}})

(defn make-storage [conn]
  (map->SqlStorage {:conn conn
                    :overrides overrides}))

(defmethod st/make-storage :sql [{conf :storage}]
  (log/info "Using SQL storage with configuration:" conf)
  (let [conn {:ds (conn/->pool HikariDataSource (-> conf
                                                    (dissoc :url :type)
                                                    (assoc :jdbcUrl (:url conf))))
              :sql-opts {:dialect :mysql :quoted-snake true}}]
    (make-storage conn)))
