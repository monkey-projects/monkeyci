(ns monkey.ci.web.api.params
  "Api functions for managing build parameters"
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [protocols :as p]
             [storage :as st]
             [vault :as v]]
            [monkey.ci.web.common :as c]))

(defn- crypto-iv
  "Looks up crypto initialization vector for the customer associated with the
   request.  If no crypto record is found, one is generated."
  [req]
  (let [cust-id (c/customer-id req)
        st (c/req->storage req)]
    (if-let [crypto (st/find-crypto st cust-id)]
      (:iv crypto)
      (let [iv (v/generate-iv)]
        (log/debug "No crypto record found for customer" cust-id ", generating a new one")
        (when (st/save-crypto st {:customer-id cust-id
                                  :iv iv})
          iv)))))

(defn- encrypt-one [req]
  (let [v (c/req->vault req)
        iv (crypto-iv req)]
    (letfn [(encrypt-vals [p]
              (map #(update % :value (partial p/encrypt v iv)) p))]
      (update-in req [:parameters :body :parameters] encrypt-vals))))

(defn- encrypt-all [req]
  (let [v (c/req->vault req)
        iv (crypto-iv req)]
    (letfn [(encrypt-vals [p]
              (map #(update % :value (partial p/encrypt v iv)) p))
            (encrypt-params [b]
              (map #(update % :parameters encrypt-vals) b))]
      (update-in req [:parameters :body] encrypt-params))))

(defn- decrypt
  "Decryps all parameter values using the vault from the request"
  [req params]
  (let [v (c/req->vault req)
        iv (crypto-iv req)]
    (letfn [(decrypt-vals [p]
              (mapv #(update % :value (partial p/decrypt v iv)) p))]
      (->> params
           (map #(update % :parameters decrypt-vals))))))

(defn- decrypt-one [req param]
  (->> (decrypt req [param])
       first))

(defn get-customer-params
  "Retrieves all parameters configured on the customer.  This is for administration purposes."
  [req]
  (c/get-list-for-customer (comp (partial decrypt req) c/drop-ids st/find-params) req))

(defn- get-param-id [req]
  (st/params-sid (c/customer-id req)
                 (get-in req [:parameters :path :param-id])))

(c/make-entity-endpoints
 "param"
 {:get-id get-param-id
  :deleter st/delete-param})

(defn- assign-customer-id [req]
  (update-in req [:parameters :body] assoc :customer-id (c/customer-id req)))

(defn get-param [req]
  (let [getter (c/entity-getter get-param-id (comp (partial decrypt-one req)
                                                   st/find-param))]
    (getter req)))

(def create-param 
  (comp (c/entity-creator st/save-param c/default-id)
        assign-customer-id
        encrypt-one))

(defn get-repo-params
  "Retrieves the parameters that are available for the given repository.  This depends
   on the parameter label filters and the repository labels."
  [req]
  (c/get-for-repo-by-label (comp (partial decrypt req) st/find-params) (mapcat :parameters) req))

(def update-params
  (comp (partial c/update-for-customer st/save-params)
        encrypt-all))
