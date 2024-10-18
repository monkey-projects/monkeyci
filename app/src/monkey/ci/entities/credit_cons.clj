(ns monkey.ci.entities.credit-cons
  (:require [monkey.ci.entities.core :as ec]))

(def basic-query
  {:select [:cc.*
            [:c.cuid :cust-cuid]
            [:r.display-id :repo-did]
            [:b.display-id :build-did]
            [:cred.cuid :cred-cuid]]
   :from [[:credit-consumptions :cc]]
   :join [[:builds :b] [:= :b.id :cc.build-id]
          [:repos :r] [:= :r.id :b.repo-id]
          [:customers :c] [:= :c.id :r.customer-id]
          [:customer-credits :cred] [:= :cred.id :cc.credit-id]]})

(defn select-credit-cons [conn f]
  (->> (assoc basic-query :where f)
       (ec/select conn)
       (map ec/convert-credit-cons-select)
       (map (fn [r]
              (-> r
                  (dissoc :cuid :cust-cuid :repo-did :build-did :cred-cuid)
                  (assoc :id (:cuid r)
                         :customer-id (:cust-cuid r)
                         :repo-id (:repo-did r)
                         :build-id (:build-did r)
                         :credit-id (:cred-cuid r)))))))

(defn by-cuid [id]
  [:= :cc.cuid id])

(defn by-cust [id]
  [:= :c.cuid id])
