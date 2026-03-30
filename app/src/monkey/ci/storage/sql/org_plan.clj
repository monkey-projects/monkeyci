(ns monkey.ci.storage.sql.org-plan
  (:require [monkey.ci.entities
             [core :as ec]
             [org-plan :as ep]]
            [monkey.ci.storage.sql.common :as sc]))

(defn- db->plan [p]
  (-> p
      (sc/cuid->id)
      (assoc :org-id (:org-cuid p)
             :subscription-id (:sub-cuid p))
      (dissoc :org-cuid :sub-cuid)))

(defn- insert-org-plan [conn plan]
  (when-let [org (ec/select-org conn (ec/by-cuid (:org-id plan)))]
    (when-let [cs (ec/select-credit-subscription conn (ec/by-cuid (:subscription-id plan)))]
      (-> plan
          (sc/id->cuid)
          (assoc :org-id (:id org)
                 :subscription-id (:id cs))
          (as-> p (ec/insert-org-plan conn p))))))

(defn- update-org-plan [conn plan existing]
  (-> plan
      (dissoc :cuid)
      (merge (select-keys existing [:org-id :subscription-id :id]))
      (as-> p (ec/update-org-plan conn p))))

(defn upsert-org-plan [conn plan]
  (if-let [match (ec/select-org-plan conn (ec/by-cuid (:id plan)))]
    (update-org-plan conn plan match)
    (insert-org-plan conn plan)))

(defn select-org-plan [conn cuid]
  (some-> (ep/select-org-plan conn cuid)
          (db->plan)))

(defn select-org-plans-for-org [st org-id]
  (->> (ep/select-org-plans-for-org (sc/get-conn st) org-id)
       (map db->plan)))
