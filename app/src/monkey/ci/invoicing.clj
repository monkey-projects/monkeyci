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

(defn list-customers
  "Lists all registered invoicing customers.  Returns a deferred with the
   http response."
  [client]
  (do-req client {:method :get
                  :path "/customer"}))

(defn create-customer
  [client cust]
  (do-req client {:method :post
                  :path "/customer"
                  :body cust}))
