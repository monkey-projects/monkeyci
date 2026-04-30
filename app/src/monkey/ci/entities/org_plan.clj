(ns monkey.ci.entities.org-plan
  (:require [monkey.ci.entities.core :as ec]))

(def base-query
  {:select [:p.* [:o.cuid :org-cuid] [:cs.cuid :sub-cuid]]
   :from [[:org-plans :p]]
   :join [[:orgs :o] [:= :o.id :p.org-id]
          [:credit-subscriptions :cs] [:= :cs.id :p.subscription-id]]})

(defn select-org-plan [conn cuid]
  (some->> (assoc base-query
                  :where [:= :p.cuid cuid])
           (ec/select conn)
           (first)
           (ec/convert-org-plan-select)))

(defn select-org-plans-for-org [conn org-cuid]
  (some->> (assoc base-query :where [:= :o.cuid org-cuid])
           (ec/select conn)
           (map ec/convert-org-plan-select)))
