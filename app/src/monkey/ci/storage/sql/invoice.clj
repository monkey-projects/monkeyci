(ns monkey.ci.storage.sql.invoice
  "SQL functions for invoices.  The invoice details are stored in the invoice records
   using edn, since we won't be referencing them directly."
  (:require [monkey.ci.entities
             [core :as ec]
             [invoice :as ei]]
            [monkey.ci.storage.sql.common :as c]))

(defn- db->invoice [inv]
  (-> inv
      (c/cuid->id)
      (assoc :org-id (:org-cuid inv))
      (dissoc :org-cuid)))

(defn select-invoice [conn cuid]
  (some-> (ei/select-invoice-with-org conn cuid)
          db->invoice))

(defn select-invoices-for-org [st org-cuid]
  (->> (ei/select-invoices-for-org (c/get-conn st) org-cuid)
       (map db->invoice)))

(defn- insert-invoice [conn inv]
  (when-let [org (ec/select-org conn (ec/by-cuid (:org-id inv)))]
    (ec/insert-invoice conn (-> inv
                                (c/id->cuid)
                                (assoc :org-id (:id org))))))

(defn- update-invoice [conn inv existing]
  (ec/update-invoice conn (merge existing
                                 (-> inv
                                     (dissoc :id :org-id)))))

(defn upsert-invoice [conn inv]
  (if-let [existing (ec/select-invoice conn (ec/by-cuid (:id inv)))]
    (update-invoice conn inv existing)
    (insert-invoice conn inv)))
