(ns monkey.ci.entities.customer
  "Customer specific query functions"
  (:require [medley.core :as mc]
            [monkey.ci.entities.core :as ec]))

(defn customer-with-repos
  "Selects customer by filter with all its repos"
  [conn f]
  (when-let [cust (ec/select-customer conn f)]
    (->> (ec/select-repos conn (ec/by-customer (:id cust)))
         (group-by :uuid)
         (mc/map-vals first)
         (assoc cust :repos))))
