(ns monkey.ci.entities.user
  (:require [monkey.ci.entities.core :as ec]))

(defn select-user-customer-cuids
  "Fetches all customers cuids linked to a user"
  [conn user-id]
  (->> (ec/select conn
                  {:select [:c.cuid]
                   :from [[:customers :c]]
                   :join [[:user-customers :uc] [:= :uc.customer-id :c.id]]
                   :where [:= :uc.user-id user-id]})
       (map :cuid)))

(defn select-user-customers
  "Selects all customers linked to this user.  This is similar to searching
   for customers, so repositories are not fetched."
  [conn user-cuid]
  (ec/select conn
             {:select [:c.*]
              :from [[:customers :c]]
              :join [[:user-customers :uc] [:= :uc.customer-id :c.id]
                     [:users :u] [:= :u.id :uc.user-id]]
              :where [:= :u.cuid user-cuid]}))
