(ns monkey.ci.storage.sql.user-token
  (:require [monkey.ci.entities
             [core :as ec]
             [token :as et]]
            [monkey.ci.storage.sql.common :as sc]))

(defn upsert-user-token [conn token]
  (let [u (->> (ec/select-users conn (ec/by-cuid (:user-id token)))
               first)
        t (-> token
              (sc/id->cuid)
              (assoc :user-id (:id u)))
        m (->> (ec/select-user-tokens conn (ec/by-cuid (:id token)))
               first)]
    (if m
      (ec/update-user-token conn (merge m t))
      (ec/insert-user-token conn t))))

(defn select-user-token [conn [user-id token-id]]
  (some-> (ec/select-user-tokens conn (ec/by-cuid token-id))
          first
          (sc/cuid->id)
          (assoc :user-id user-id)))

(defn select-user-tokens [st user-id]
  (->> (et/select-user-tokens (sc/get-conn st) (et/by-user user-id))
       (map sc/cuid->id)))

(defn select-user-token-by-token [st token]
  (->> (et/select-user-tokens (sc/get-conn st) (et/by-token token))
       (map sc/cuid->id)
       first))

(defn delete-user-token [conn token-id]
  (ec/delete-entities conn :user-tokens (ec/by-cuid token-id)))
