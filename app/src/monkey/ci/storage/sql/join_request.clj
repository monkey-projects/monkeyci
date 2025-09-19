(ns monkey.ci.storage.sql.join-request
  (:require [monkey.ci.entities
             [core :as ec]
             [join-request :as jr]]
            [monkey.ci.storage.sql.common :as sc]))

(defn- insert-join-request [conn jr]
  (let [user (ec/select-user conn (ec/by-cuid (:user-id jr)))
        org (ec/select-org conn (ec/by-cuid (:org-id jr)))
        e (-> jr
              (sc/id->cuid)
              (select-keys [:cuid :status :request-msg :response-msg])
              (update :status name)
              (assoc :org-id (:id org)
                     :user-id (:id user)))]
    (ec/insert-join-request conn e)))

(defn- update-join-request [conn jr existing]
  (ec/update-join-request conn
                          (-> (select-keys jr [:status :request-msg :response-msg])
                              (update :status name)
                              (as-> x (merge existing x)))))

(defn upsert-join-request [conn jr]
  (if-let [existing (ec/select-join-request conn (ec/by-cuid (:id jr)))]
    (update-join-request conn jr existing)
    (insert-join-request conn jr)))

(defn select-join-request [conn cuid]
  (jr/select-join-request-as-entity conn cuid))

(defn select-user-join-requests [st user-cuid]
  (letfn [(db->jr [r]
            (update r :status keyword))]
    (->> (jr/select-user-join-requests (sc/get-conn st) user-cuid)
         (map db->jr))))

