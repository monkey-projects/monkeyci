(ns monkey.ci.storage.sql.job
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [build :as eb]
             [core :as ec]
             [job :as ej]]
            [monkey.ci.storage.sql.common :as sc]))

(defn- job->db [job]
  (-> job
      (select-keys [:status :start-time :end-time :credit-multiplier])
      (mc/update-existing :status (fnil identity :error))
      (assoc :display-id (:id job)
             :details (dissoc job :id :status :start-time :end-time))))

(defn- db->job [job]
  (-> job
      (select-keys [:status :start-time :end-time])
      (merge (:details job))
      (assoc :id (:display-id job))
      (sc/drop-nil)))

(defn select-build-jobs [conn build-id]
  (->> (ec/select-jobs conn (ec/by-build build-id))
       (map db->job)
       (map (juxt :id identity))
       (into {})))

(defn- insert-job [conn job build-sid]
  (when-let [build (apply eb/select-build-by-sid conn build-sid)]
    (ec/insert-job conn (-> job
                            (job->db)
                            (assoc :build-id (:id build))))
    build-sid))

(defn- update-job [conn job existing]
  (let [upd (-> existing
                (dissoc :org-cuid :repo-display-id :build-display-id)
                (merge (job->db job)))]
    (when (ec/update-job conn upd)
      ;; Return build sid
      ((juxt :org-cuid :repo-display-id :build-display-id) existing))))

(defn upsert-job [st build-sid job]
  (let [conn (sc/get-conn st)]
    (if-let [existing (ej/select-by-sid conn (concat build-sid [(:id job)]))]
      (update-job conn job existing)
      (insert-job conn job build-sid))))

(defn select-job [st job-sid]
  (some-> (ej/select-by-sid (sc/get-conn st) job-sid)
          (db->job)))
