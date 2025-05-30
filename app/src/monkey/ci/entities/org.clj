(ns monkey.ci.entities.org
  "Organization specific query functions"
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [repo :as r]]))

(defn org-with-repos
  "Selects org by filter with all its repos"
  [conn f]
  (when-let [org (ec/select-org conn f)]
    (->> (r/repos-with-labels conn (ec/by-org (:id org)))
         (group-by :cuid)
         (mc/map-vals first)
         (assoc org :repos))))

(defn org-ids-by-cuids
  "Fetches all org ids for given cuids"
  [conn cuids]
  (when (not-empty cuids)
    (->> (ec/select conn
                    {:select [:c.id]
                     :from [[:orgs :c]]
                     :where [:in :c.cuid cuids]})
         (map :id))))

(defn crypto-by-org-cuid [conn cuid]
  (some-> (ec/select conn
                     {:select [:cr.*]
                      :from [[:cryptos :cr]]
                      :join [[:orgs :c] [:= :c.id :cr.org-id]]
                      :where [:= :c.cuid cuid]})
          (first)
          (assoc :org-id cuid)))
