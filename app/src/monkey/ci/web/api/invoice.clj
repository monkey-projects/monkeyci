(ns monkey.ci.web.api.invoice
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def invoice-sid (juxt c/customer-id (comp :invoice-id :path :parameters)))

(c/make-entity-endpoints
 "invoice"
 {:get-id invoice-sid
  :getter st/find-invoice})

(defn search-invoices
  "Searches customer invoices"
  [req]
  ;; TODO Apply filter
  (let [inv (st/list-invoices-for-customer (c/req->storage req)
                                           (c/customer-id req))]
    (rur/response inv)))
