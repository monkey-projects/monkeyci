(ns monkey.ci.storage.sql.org-token
  (:require [clojure.tools.logging :as log]
            [monkey.ci.entities
             [core :as ec]
             [token :as et]]
            [monkey.ci.storage.sql.common :as sc]))

(defn upsert-org-token [conn token]
  (let [u (->> (ec/select-orgs conn (ec/by-cuid (:org-id token)))
               first)
        t (-> token
              (sc/id->cuid)
              (assoc :org-id (:id u)))
        m (->> (ec/select-org-tokens conn (ec/by-cuid (:id token)))
               first)]
    (if m
      (ec/update-org-token conn (merge m t))
      (ec/insert-org-token conn t))))

(defn select-org-token [conn [org-id token-id]]
  (some-> (ec/select-org-tokens conn (ec/by-cuid token-id))
          first
          (sc/cuid->id)
          (assoc :org-id org-id)))

(defn select-org-tokens [st org-id]
  (->> (et/select-org-tokens (sc/get-conn st) (et/by-org org-id))
       (map sc/cuid->id)))

(defn select-org-token-by-token [st token]
  (->> (et/select-org-tokens (sc/get-conn st) (et/by-token token))
       (map sc/cuid->id)
       first))

(defn delete-org-token [conn token-id]
  (ec/delete-entities conn :org-tokens (ec/by-cuid token-id)))
