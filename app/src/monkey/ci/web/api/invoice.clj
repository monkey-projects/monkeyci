(ns monkey.ci.web.api.invoice
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def invoice-sid (juxt c/org-id (comp :invoice-id :path :parameters)))

(c/make-entity-endpoints
 "invoice"
 {:get-id invoice-sid
  :getter st/find-invoice})

(defn search-invoices
  "Searches org invoices"
  [req]
  ;; TODO Apply filter
  (let [inv (st/list-invoices-for-org (c/req->storage req)
                                      (c/org-id req))]
    (rur/response inv)))
