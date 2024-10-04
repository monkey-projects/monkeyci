(ns monkey.ci.entities.customer-credit
  (:require [monkey.ci.entities.core :as ec]))

(defn select-customer-credits [conn f]
  (->> {:select [:cc.amount :cc.from-time
                 [:cc.cuid :id]
                 [:c.cuid :customer-id]]
        :from [[:customer-credits :cc]]
        :join [[:customers :c] [:= :c.id :cc.customer-id]]
        :where f}
       (ec/select conn)
       (map ec/convert-credit-conversions-select)))

(defn by-cuid [id]
  [:= :cc.cuid id])

(defn by-customer-since [cust-id ts]
  [:and
   [:= :c.cuid cust-id]
   [:<= :cc.from-time (ec/->ts ts)]])
