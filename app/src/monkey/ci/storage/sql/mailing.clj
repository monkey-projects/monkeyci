(ns monkey.ci.storage.sql.mailing
  (:require [monkey.ci.entities.core :as ec]
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
