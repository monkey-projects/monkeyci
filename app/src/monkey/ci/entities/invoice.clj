(ns monkey.ci.entities.invoice
  (:require [monkey.ci.entities.core :as ec]))

(def base-query
  {:select [:i.* [:c.cuid :customer-cuid]]
   :from [[:invoices :i]]
   :join [[:customers :c] [:= :c.id :i.customer-id]]})

(defn select-invoice-with-customer [conn cuid]
  (->> (assoc base-query
              :where [:= :i.cuid cuid])
       (ec/select conn)
       (map ec/convert-inv-select)
       first))

(defn select-invoices-for-customer [conn cust-cuid]
  (->> (assoc base-query
              :where [:= :c.cuid cust-cuid])
       (ec/select conn)
       (map ec/convert-inv-select)))
