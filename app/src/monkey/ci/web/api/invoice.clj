(ns monkey.ci.web.api.invoice
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [invoicing :as i]
             [storage :as st]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def req->client
  "Gets the invoicing client from the request runtime"
  (comp :client :invoicing c/req->rt))

(def invoice-sid (juxt c/org-id (comp :invoice-id :path :parameters)))

(c/make-entity-endpoints
 "invoice"
 {:get-id invoice-sid
  :getter st/find-invoice})

(defn create-invoice [req]
  (st/with-transaction (c/req->storage req) st
    (let [org-id (c/org-id req)
          inv (-> (c/body req)
                  (assoc :id (st/new-id)
                         :org-id org-id))
          client (req->client req)]
      (if (st/save-invoice st inv)
        (let [ext-cust-id (some-> (st/find-org-invoicing st org-id)
                                  :ext-id)]
          (if-let [inv-cust @(i/get-customer client ext-cust-id)]
            (let [ext-inv (:body @(i/create-invoice client inv))
                  upd (assoc inv
                             :ext-id (str (:id ext-inv))
                             :invoice-nr (:invoice-nr ext-inv))]
              (log/debug "External invoice created:" (:invoice-nr ext-inv))
              (if (st/save-invoice st upd)
                (-> (rur/response upd)
                    (rur/status 201))
                (c/error-response "Unable to save invoice" 500)))
            (c/error-response (str "Invoice customer not found: " ext-cust-id))))
        (c/error-response "Unable to create invoice" 500)))))

(defn search-invoices
  "Searches org invoices"
  [req]
  ;; TODO Apply filter
  (let [inv (st/list-invoices-for-org (c/req->storage req)
                                      (c/org-id req))]
    (rur/response inv)))
