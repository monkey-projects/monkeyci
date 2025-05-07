(ns monkey.ci.entities.credit-subs
  (:require [monkey.ci.entities.core :as ec]))

(defn select-credit-subs [conn f]
  (->> {:select [:cs.* [:c.cuid :org-cuid]]
        :from [[:credit-subscriptions :cs]]
        :join [[:orgs :c] [:= :c.id :cs.org-id]]
        :where f}
       (ec/select conn)
       (map ec/convert-credit-sub-select)
       (map (fn [r]
              (-> r
                  (dissoc :cuid :org-cuid)
                  (assoc :id (:cuid r)
                         :org-id (:org-cuid r)))))))

(defn by-cuid [id]
  [:= :cs.cuid id])

(defn by-org [id]
  [:= :c.cuid id])

(defn active-at [at]
  (let [ts (ec/->ts at)]
    [:and
     [:<= :cs.valid-from ts]
     [:or
      [:is :cs.valid-until nil]
      [:<= ts :cs.valid-until]]]))
