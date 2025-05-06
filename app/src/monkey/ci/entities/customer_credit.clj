(ns monkey.ci.entities.customer-credit
  (:require [monkey.ci.entities
             [core :as ec]
             [credit-cons :as ecc]]))

(def base-query
  {:from [[:customer-credits :cc]]
   :join [[:customers :c] [:= :c.id :cc.org-id]]
   :left-join [[:credit-subscriptions :cs] [:= :cs.id :cc.subscription-id]
               [:users :u] [:= :u.id :cc.user-id]]})

(defn select-customer-credits [conn f]
  (->> (assoc base-query
              :select [:cc.amount :cc.from-time :cc.type :cc.reason
                       [:cc.cuid :id]
                       [:c.cuid :org-id]
                       [:cs.cuid :subscription-id]
                       [:u.cuid :user-id]]
              :where f)
       (ec/select conn)
       (map ec/convert-credit-select)))

(defn select-avail-credits-amount [conn cust-id]
  (let [avail (-> (ec/select
                   conn
                   (assoc base-query
                          :select [[[:sum :cc.amount] :avail]]
                          :where [:= :c.cuid cust-id]))
                  (first)
                  :avail
                  (or 0M))
        used  (-> (ec/select
                   conn
                   (-> ecc/basic-query
                       (assoc :select [[[:sum :cc.amount] :used]]
                              :where (ecc/by-cust cust-id))))
                  (first)
                  :used)]
    (- (or avail 0) (or used 0))))

(defn select-avail-credits [conn cust-id]
  (->> (ec/select
        conn
        {:select [:cc.amount :cc.from-time :cc.type :cc.reason
                  [:cc.cuid :id]
                  [:c.cuid :org-id]
                  [:cs.cuid :subscription-id]
                  [:u.cuid :user-id]
                  [:cco.amount :used]]
         :from [[:credit-consumptions :cco]]
         :left-join [[:customer-credits :cc] [:= :cc.id :cco.credit-id]
                     [:credit-subscriptions :cs] [:= :cs.id :cc.subscription-id]
                     [:users :u] [:= :u.id :cc.user-id]
                     [:customers :c] [:= :c.id :cc.org-id]]
         :where [:= :c.cuid cust-id]
         ;; Group by amount required by mysql
         :group-by [:cc.id :cco.amount]
         :having [:> [:- :cc.amount :used] 0]})
       (map #(dissoc % :used))
       (map ec/convert-credit-select)))

(defn by-cuid [id]
  [:= :cc.cuid id])

(defn by-cust [cust-id]
  [:= :c.cuid cust-id])

(defn by-cust-since [cust-id ts]
  [:and
   (by-cust cust-id)
   [:or
    [:is :cc.from-time nil]
    [:<= :cc.from-time (ec/->ts ts)]]])

