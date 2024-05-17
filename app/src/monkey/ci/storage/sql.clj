(ns monkey.ci.storage.sql
  "Storage implementation that uses an SQL database for persistence.  This namespace provides
   a layer on top of the entities namespace to perform the required queries whenever a 
   document is saved or loaded."
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [customer :as ecu]]
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
  (log/debug "Converting repo:" re)
  (cond->
      (-> re
          (dissoc :uuid :customer-id :display-id)
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
        {:keys [insert update delete]} (lbl/reconcile-labels ex labels)]
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
      (assoc :uuid (parse-uuid (:id cust)))))

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
  (if-let [existing (ec/select-customer conn (ec/by-uuid (parse-uuid (:id cust))))]
    (update-customer conn cust existing)
    (insert-customer conn cust)))

(defn- select-customer [conn uuid]
  (letfn [(entities->repos [repos]
            (reduce-kv (fn [r _ v]
                         (assoc r (:display-id v) (db->repo v)))
                       {}
                       repos))
          (db->cust [c]
            (-> c
                (dissoc :uuid)
                (assoc :id (str (:uuid c)))
                (update :repos entities->repos)))]
    (when uuid
      (some-> (ecu/customer-with-repos conn (ec/by-uuid uuid))
              (db->cust)))))

(defn- customer-exists? [conn uuid]
  (some? (ec/select-customer conn (ec/by-uuid uuid))))

(defn- delete-customer [conn uuid]
  (when uuid
    (ec/delete-customers conn (ec/by-uuid uuid))))

(defn- global-sid? [type sid]
  (= [st/global (name type)] (take 2 sid)))

(def customer? (partial global-sid? :customers))
(def webhook? (partial global-sid? :webhooks))

(defn- global-sid->uuid [sid]
  (some-> (nth sid 2)
          ;; This assumes the id in the sid is a uuid
          (parse-uuid)))

(defn- insert-webhook [conn wh]
  (if-let [cust (ec/select-customer conn (ec/by-uuid (parse-uuid (:customer-id wh))))]
    (if-let [repo (ec/select-repo conn [:and
                                        (ec/by-display-id (:repo-id wh))
                                        (ec/by-customer (:id cust))])]
      (let [we {:uuid (parse-uuid (:id wh))
                :repo-id (:id repo)
                :secret (:secret-key wh)}]
        (ec/insert-webhook conn we))
      (throw (ex-info "Repository does not exist" wh)))
    (throw (ex-info "Customer does not exist" wh))))

(defn- update-webhook [conn wh existing])

(defn- upsert-webhook [conn wh]
  (spec/valid? :entity/webhook wh)
  (if-let [existing (ec/select-webhook conn (ec/by-uuid (parse-uuid (:id wh))))]
    (update-webhook conn wh existing)
    (insert-webhook conn wh)))

(defn- select-webhook [conn uuid]
  (when-let [we (ec/select-webhook conn (ec/by-uuid uuid))]
    (let [repo (ec/select-repo conn (ec/by-id (:repo-id we)))
          cust (ec/select-customer conn (ec/by-id (:customer-id repo)))]
      {:id (str (:uuid we))
       :secret-key (:secret we)
       :repo-id (:display-id repo)
       :customer-id (str (:uuid cust))})))

(defrecord SqlStorage [conn]
  p/Storage
  (read-obj [_ sid]
    (cond
      (customer? sid)
      (select-customer conn (global-sid->uuid sid))
      (webhook? sid)
      (select-webhook conn (global-sid->uuid sid))))
  
  (write-obj [_ sid obj]
    (cond
      (customer? sid)
      (upsert-customer conn obj)
      (webhook? sid)
      (upsert-webhook conn obj))
    sid)

  (obj-exists? [_ sid]
    (when (customer? sid)
      (customer-exists? conn (global-sid->uuid sid))))

  (delete-obj [_ sid]
    (deleted?
     (when (customer? sid)
       (delete-customer conn (global-sid->uuid sid))))))

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
        add-cust-uuid (fn [r e]
                        (assoc r :customer-id (str (get-in customers [(:customer-id e) :uuid]))))
        convert (fn [e]
                  (db->repo e add-cust-uuid))]
    (map convert matches)))

(defn watch-github-repo [{:keys [conn]} {:keys [customer-id] :as repo}]
  (when-let [cust (ec/select-customer conn (ec/by-uuid (parse-uuid customer-id)))]
    (let [r (ec/insert-repo conn (repo->db repo (:id cust)))]
      (sid/->sid [customer-id (:display-id r)]))))

(defn unwatch-github-repo [{:keys [conn]} [customer-id repo-id]]
  ;; TODO Use a single query with join
  (= 1 (when-let [cust (ec/select-customer conn (ec/by-uuid (parse-uuid customer-id)))]
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
