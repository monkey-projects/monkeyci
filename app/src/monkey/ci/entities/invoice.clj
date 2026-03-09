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

(defn select-invoices-for-org [conn org-cuid]
  (->> (assoc base-query
              :where [:= :c.cuid org-cuid])
       (ec/select conn)
       (map ec/convert-inv-select)))

(defn select-org-invoicing-for-org [conn org-cuid]
  (->> {:select [:i.*]
        :from [[:org-invoicings :i]]
        :join [[:orgs :o] [:= :o.id :i.org-id]]
        :where [:= :o.cuid org-cuid]}
       (ec/select conn)
       first))
