(ns monkey.ci.entities.customer
  "Customer specific query functions"
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [repo :as r]]))

(defn customer-with-repos
  "Selects customer by filter with all its repos"
  [conn f]
  (when-let [cust (ec/select-customer conn f)]
    (->> (r/repos-with-labels conn (ec/by-customer (:id cust)))
         (group-by :cuid)
         (mc/map-vals first)
         (assoc cust :repos))))

(defn customer-ids-by-cuids
  "Fetches all customer ids for given cuids"
  [conn cuids]
  (when (not-empty cuids)
    (->> (ec/select conn
                    {:select [:c.id]
                     :from [[:customers :c]]
                     :where [:in :c.cuid cuids]})
         (map :id))))

(defn crypto-by-cust-cuid [conn cuid]
  (some-> (ec/select conn
                     {:select [:cr.*]
                      :from [[:cryptos :cr]]
                      :join [[:customers :c] [:= :c.id :cr.customer-id]]
                      :where [:= :c.cuid cuid]})
          (first)
          (assoc :customer-id cuid)))
