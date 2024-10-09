(ns monkey.ci.entities.customer-credit
  (:require [monkey.ci.entities
             [build :as eb]
             [core :as ec]]))

(def base-query
  {:from [[:customer-credits :cc]]
   :join [[:customers :c] [:= :c.id :cc.customer-id]]})

(defn select-customer-credits [conn f]
  (->> (assoc base-query
              :select [:cc.amount :cc.from-time
                       [:cc.cuid :id]
                       [:c.cuid :customer-id]]
              :where f)
       (ec/select conn)
       (map ec/convert-credit-conversions-select)))

(defn select-avail-credits [conn cust-id]
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
                   (assoc eb/basic-query
                          :select [[[:sum :b.credits] :used]]
                          :where [:= :c.cuid cust-id]))
                  (first)
                  :used)]
    (- avail used)))

(defn by-cuid [id]
  [:= :cc.cuid id])

(defn by-customer-since [cust-id ts]
  [:and
   [:= :c.cuid cust-id]
   [:<= :cc.from-time (ec/->ts ts)]])
