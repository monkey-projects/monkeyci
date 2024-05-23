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
