(ns monkey.ci.storage.sql.mailing
  (:require [monkey.ci.entities
             [core :as ec]
             [mailing :as em]]
            [monkey.ci.storage.sql.common :as sc]))

(defn- mailing->db [m]
  (sc/id->cuid m))

(defn- db->mailing [r]
  (-> (sc/cuid->id r)
      (sc/drop-nil)))

(defn- insert-mailing [conn m]
  (when (ec/insert-mailing conn (mailing->db m))
    m))

(defn- update-mailing [conn m e]
  (let [upd (merge e (mailing->db m))]
    (ec/update-mailing conn upd)
    (db->mailing upd)))

(defn upsert-mailing [conn m]
  (if-let [e (first (ec/select-mailings conn (ec/by-cuid (:id m))))]
    (update-mailing conn m e)
    (insert-mailing conn m)))

(defn select-mailing [conn cuid]
  (some-> (ec/select-mailings conn (ec/by-cuid cuid))
          (first)
          (db->mailing)))

(defn select-mailings [st]
  (->> (ec/select-mailings (sc/get-conn st) nil)
       (map db->mailing)))

(defn delete-mailing [conn cuid]
  (ec/delete-entities conn :mailings (ec/by-cuid cuid)))

(def ^:private sent-mailing->db mailing->db)

(defn db->sent-mailing [sm mid]
  (-> (db->mailing sm)
      (assoc :mailing-id mid)))

(def sent-mailing->sid (juxt :mailing-id :id))

(defn- insert-sent-mailing [conn sm]
  (let [m (ec/select-mailing conn (ec/by-cuid (:mailing-id sm)))]
    (when (ec/insert-sent-mailing conn (-> (sent-mailing->db sm)
                                           (assoc :mailing-id (:id m))))
      (sent-mailing->sid sm))))

(defn- update-sent-mailing [conn sm e]
  (let [m (ec/select-mailing conn (ec/by-cuid (:mailing-id sm)))
        upd (merge e (sent-mailing->db sm))]
    (when (ec/update-sent-mailing conn upd)
      (sent-mailing->sid sm))))

(defn upsert-sent-mailing [st {:keys [id] :as sm}]
  (let [conn (sc/get-conn st)]
    (if-let [e (when id (ec/select-sent-mailing conn id))]
      (update-sent-mailing conn sm e)
      (insert-sent-mailing conn sm))))

(defn select-sent-mailing [st [mid id]]
  (some-> (ec/select-sent-mailing (sc/get-conn st) (ec/by-cuid id))
          (db->sent-mailing mid)))

(defn select-sent-mailings [st mid]
  (->> (em/select-sent-mailings (sc/get-conn st) mid)
       (map #(db->sent-mailing % mid))))
