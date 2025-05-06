(ns monkey.ci.entities.credit-subs
  (:require [monkey.ci.entities.core :as ec]))

(defn select-credit-subs [conn f]
  (->> {:select [:cs.* [:c.cuid :cust-cuid]]
        :from [[:credit-subscriptions :cs]]
        :join [[:customers :c] [:= :c.id :cs.org-id]]
        :where f}
       (ec/select conn)
       (map ec/convert-credit-sub-select)
       (map (fn [r]
              (-> r
                  (dissoc :cuid :cust-cuid)
                  (assoc :id (:cuid r)
                         :org-id (:cust-cuid r)))))))

(defn by-cuid [id]
  [:= :cs.cuid id])

(defn by-cust [id]
  [:= :c.cuid id])

(defn active-at [at]
  (let [ts (ec/->ts at)]
    [:and
     [:<= :cs.valid-from ts]
     [:or
      [:is :cs.valid-until nil]
      [:<= ts :cs.valid-until]]]))
