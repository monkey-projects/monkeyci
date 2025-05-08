(ns monkey.ci.entities.invoice
  (:require [monkey.ci.entities.core :as ec]))

(def base-query
  {:select [:i.* [:c.cuid :org-cuid]]
   :from [[:invoices :i]]
   :join [[:orgs :c] [:= :c.id :i.org-id]]})

(defn select-invoice-with-org [conn cuid]
  (->> (assoc base-query
              :where [:= :i.cuid cuid])
       (ec/select conn)
       (map ec/convert-inv-select)
       first))

(defn select-invoices-for-org [conn cust-cuid]
  (->> (assoc base-query
              :where [:= :c.cuid cust-cuid])
       (ec/select conn)
       (map ec/convert-inv-select)))
