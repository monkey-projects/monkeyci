(ns monkey.ci.storage.sql
  "Storage implementation that uses an SQL database for persistence.  This namespace provides
   a layer on top of the entities namespace."
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [customer :as ecu]]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]))

(defn- repo->entity
  "Converts the repository into an entity that can be sent to the database."
  [r cust-id]
  (-> r
      (dissoc :id)
      (assoc :display-id (:id r)
             :customer-id cust-id)))

(defn- entity->repo
  "Converts the repo entity (a db record) into a repository."
  [re]
  (-> re
      (dissoc :uuid :customer-id :display-id)
      (assoc :id (:display-id re))
      (as-> x (mc/filter-vals some? x))))

(defn- insert-repo [conn re]
  (ec/insert-repo conn re))

(defn- update-repo [conn re existing]
  (when (not= re existing)
    (ec/update-repo conn (merge existing re))))

(defn- upsert-repo [conn repo cust-id]
  (let [re (repo->entity repo cust-id)]
    (if-let [existing (ec/select-repo conn [:and
                                            (ec/by-customer cust-id)
                                            (ec/by-display-id (:id repo))])]
      (update-repo conn re existing)
      (insert-repo conn re))))

(defn- upsert-repos [conn {:keys [repos]} cust-id]
  (doseq [[id r] repos]
    (upsert-repo conn r cust-id)))

(defn- cust->entity [cust]
  (-> cust
      (select-keys [:name])
      (assoc :uuid (parse-uuid (:id cust)))))

(defn- insert-customer [conn cust]
  (let [cust-id (:id (ec/insert-customer conn (cust->entity cust)))]
    (upsert-repos conn cust cust-id)))

(defn- update-customer [conn cust existing]
  (let [ce (cust->entity cust)]
    (when (not= ce existing)
      (ec/update-customer conn (merge existing ce)))
    (upsert-repos conn cust (:id existing))))

(defn- upsert-customer [conn cust]
  (if-let [existing (ec/select-customer conn (ec/by-uuid (parse-uuid (:id cust))))]
    (update-customer conn cust existing)
    (insert-customer conn cust)))

(defn- select-customer [conn uuid]
  (letfn [(entities->repos [repos]
            (reduce-kv (fn [r _ v]
                         (assoc r (:display-id v) (entity->repo v)))
                       {}
                       repos))
          (cust->storage [c]
            (-> c
                (dissoc :uuid)
                (assoc :id (str (:uuid c)))
                (update :repos entities->repos)))]
    (when uuid
      (some-> (ecu/customer-with-repos conn (ec/by-uuid (parse-uuid uuid)))
              (cust->storage)))))

(defn- customer? [sid]
  (= [st/global "customers"] (take 2 sid)))

(defrecord SqlStorage [conn]
  p/Storage
  (read-obj [_ sid]
    (when (customer? sid)
      (select-customer conn (nth sid 2))))
  
  (write-obj [_ sid obj]
    (when (customer? sid)
      (upsert-customer conn obj))
    sid))

(defn make-storage [conn]
  (->SqlStorage conn))
