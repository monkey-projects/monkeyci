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
