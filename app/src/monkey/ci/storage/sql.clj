(ns monkey.ci.storage.sql
  "Storage implementation that uses an SQL database for persistence.  This namespace provides
   a layer on top of the entities namespace to perform the required queries whenever a 
   document is saved or loaded."
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [customer :as ecu]
             [param :as eparam]
             [ssh-key :as essh]
             [webhook :as ewh]]
            [monkey.ci
             [labels :as lbl]
             [protocols :as p]
             [sid :as sid]
             [spec :as spec]
             [storage :as st]]
            [monkey.ci.spec.db-entities]
            [monkey.ci.spec.entities]))

(def deleted? (fnil pos? 0))

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
          ;; Drop nil properties
          (as-> x (mc/filter-vals some? x)))
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
    (upsert-repos conn cust cust-id)))

(defn- update-customer [conn cust existing]
  (let [ce (cust->db cust)]
    (spec/valid? :db/customer ce)
    (when (not= ce existing)
      (ec/update-customer conn (merge existing ce)))
    (upsert-repos conn cust (:id existing))))

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
    (upsert-ssh-key conn k)))

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
        (upsert-param conn p cust-id)))))

(defn- select-params [conn customer-id]
  ;; Select customer params and values for customer cuid
  (eparam/select-customer-params-with-values conn customer-id))

(defrecord SqlStorage [conn]
  p/Storage
  (read-obj [_ sid]
    (cond
      (customer? sid)
      (select-customer conn (global-sid->cuid sid))
      (webhook? sid)
      (select-webhook conn (global-sid->cuid sid))
      (ssh-key? sid)
      (select-ssh-keys conn (second sid))
      (params? sid)
      (select-params conn (second sid))))
  
  (write-obj [_ sid obj]
    (cond
      (customer? sid)
      (upsert-customer conn obj)
      (webhook? sid)
      (upsert-webhook conn obj)
      (ssh-key? sid)
      (upsert-ssh-keys conn obj)
      (params? sid)
      (upsert-params conn obj))
    sid)

  (obj-exists? [_ sid]
    (when (customer? sid)
      (customer-exists? conn (global-sid->cuid sid))))

  (delete-obj [_ sid]
    (deleted?
     (when (customer? sid)
       (delete-customer conn (global-sid->cuid sid))))))

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
