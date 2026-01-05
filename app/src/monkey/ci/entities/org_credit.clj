(ns monkey.ci.entities.org-credit
  (:require [monkey.ci.entities
             [core :as ec]
             [credit-cons :as ecc]]))

(def base-query
  {:from [[:org-credits :cc]]
   :join [[:orgs :c] [:= :c.id :cc.org-id]]
   :left-join [[:credit-subscriptions :cs] [:= :cs.id :cc.subscription-id]
               [:users :u] [:= :u.id :cc.user-id]]})

(defn select-org-credits [conn f]
  (->> (assoc base-query
              :select [:cc.amount :cc.valid-from :cc.valid-until :cc.type :cc.reason
                       [:cc.cuid :id]
                       [:c.cuid :org-id]
                       [:cs.cuid :subscription-id]
                       [:u.cuid :user-id]]
              :where f)
       (ec/select conn)
       (map ec/convert-credit-select)))

(defn select-avail-credits-amount [conn org-id ts]
  (let [at (when ts (ec/->ts ts))
        avail (-> (ec/select
                   conn
                   (assoc base-query
                          :select [[[:sum :cc.amount] :avail]]
                          :where (cond->> [:= :c.cuid org-id]
                                   at (conj [:and
                                             [:or
                                              [:= :cc.valid-from nil]
                                              [:<= :cc.valid-from at]]
                                             [:or
                                              [:= :cc.valid-until nil]
                                              [:<= at :cc.valid-until]]]))))
                  (first)
                  :avail
                  (or 0M))
        used  (-> (ec/select
                   conn
                   (-> ecc/basic-query
                       (assoc :select [[[:sum :cc.amount] :used]]
                              :where (ecc/by-org org-id))))
                  (first)
                  :used)]
    (max 0M (- (or avail 0) (or used 0)))))

(defn select-avail-credits [conn org-id]
  (->> (ec/select
        conn
        {:select [:cc.amount :cc.valid-from :cc.valid-until :cc.type :cc.reason
                  [:cc.cuid :id]
                  [:c.cuid :org-id]
                  [:cs.cuid :subscription-id]
                  [:u.cuid :user-id]
                  [:cco.amount :used]]
         :from [[:credit-consumptions :cco]]
         :left-join [[:org-credits :cc] [:= :cc.id :cco.credit-id]
                     [:credit-subscriptions :cs] [:= :cs.id :cc.subscription-id]
                     [:users :u] [:= :u.id :cc.user-id]
                     [:orgs :c] [:= :c.id :cc.org-id]]
         :where [:= :c.cuid org-id]
         ;; Group by amount required by mysql
         :group-by [:cc.id :cco.amount]
         :having [:> [:- :cc.amount :used] 0]})
       (map #(dissoc % :used))
       (map ec/convert-credit-select)))

(defn by-cuid [id]
  [:= :cc.cuid id])

(defn by-org [org-id]
  [:= :c.cuid org-id])

(defn by-org-since [org-id ts]
  [:and
   (by-org org-id)
   [:or
    [:is :cc.valid-from nil]
    [:<= :cc.valid-from (ec/->ts ts)]]])
