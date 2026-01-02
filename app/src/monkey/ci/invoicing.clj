(ns monkey.ci.invoicing
  "Functions for interacting with the external invoicing microservice"
  (:require [aleph.http :as http]
            [aleph.http.client-middleware :as mw]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [manifold.deferred :as md]
            [monkey.ci.web.common :as wc]))

(def ^:private middleware
  (comp mw/wrap-method
        mw/wrap-accept
        mw/wrap-content-type))

(defn- make-request [client {:keys [body path] :as opt}]
  (merge client
         (dissoc opt :path)
         (cond-> {:url (str (:url client) path)
                  :middleware middleware}
           body (assoc :content-type "application/json"
                       :body (json/generate-string body {:key-fn (comp csk/->camelCase name)})))))

(defn- do-req [client opts]
  (-> client
      (make-request opts)
      (http/request)
      (md/chain wc/parse-body)))

(defn make-client
  "Creates a new invoicing client with given config.  The client is a 1-arity
   function that accepts request options, and executes an async http request."
  [conf]
  (fn [req]
    (do-req conf req)))

(defn- inv-req [builder]
  (fn [client & args]
    (client (apply builder args))))

(def list-customers
  "Lists all registered invoicing customers.  Returns a deferred with the
   http response."
  (inv-req (constantly {:method :get
                        :path "/customer"})))

(def create-customer
  (inv-req (fn [cust]
             {:method :post
              :path "/customer"
              :body cust})))

(def update-customer
  (inv-req (fn [id cust]
             {:method :put
              :path (str "/customer/" id)
              :body cust})))

(defn get-customer [client id]
  (md/chain
   (list-customers client)
   :body
   (partial filter (comp (partial = id) str :id))
   first))

(def list-invoices
  "Lists all invoices.  Returns a deferred with the http response."
  ;; TODO Filtering
  (inv-req (constantly {:method :get
                        :path "/invoice"})))

(def create-invoice
  (inv-req (fn [inv]
             {:method :post
              :path "/invoice"
              :body inv})))
