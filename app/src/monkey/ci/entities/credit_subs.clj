(ns monkey.ci.entities.credit-subs
  (:require [monkey.ci.entities.core :as ec]))

(defn select-credit-subs [conn f]
  (->> {:select [:cs.* [:c.cuid :cust-cuid]]
        :from [[:credit-subscriptions :cs]]
        :join [[:customers :c] [:= :c.id :cs.customer-id]]
        :where f}
       (ec/select conn)
       (map ec/convert-credit-sub-select)
       (map (fn [r]
              (-> r
                  (dissoc :cuid :cust-cuid)
                  (assoc :id (:cuid r)
                         :customer-id (:cust-cuid r)))))))

(defn by-cuid [id]
  [:= :cs.cuid id])

(defn by-cust [id]
  [:= :c.cuid id])
