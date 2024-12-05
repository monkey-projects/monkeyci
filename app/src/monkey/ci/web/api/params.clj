(ns monkey.ci.web.api.params
  "Api functions for managing build parameters"
  (:require [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.web.common :as c]))

(defn- decrypt
  "Decryps all parameter values using the vault from the request"
  [req params]
  (let [v (c/req->vault req)]
    (letfn [(decrypt-vals [p]
              (mapv #(update % :value (partial p/decrypt v nil)) p))]
      (->> params
           (map #(update % :parameters decrypt-vals))))))

(defn get-customer-params
  "Retrieves all parameters configured on the customer.  This is for administration purposes."
  [req]
  (c/get-list-for-customer (comp (partial decrypt req) c/drop-ids st/find-params) req))

(c/make-entity-endpoints
 "param"
 ;; TODO Encrypt/decrypt
 {:get-id (fn [req]
            (st/params-sid (c/customer-id req)
                           (get-in req [:parameters :path :param-id])))
  :getter st/find-param
  :saver st/save-param
  :deleter st/delete-param})

(defn- assign-customer-id [req]
  (update-in req [:parameters :body] assoc :customer-id (c/customer-id req)))

(defn- encrypt-one [req]
  (let [v (c/req->vault req)]
    (letfn [(encrypt-vals [p]
              ;; TODO USe customer iv
              (map #(update % :value (partial p/encrypt v nil)) p))]
      (update-in req [:parameters :body :parameters] encrypt-vals))))

(defn- encrypt-all [req]
  (let [v (c/req->vault req)]
    (letfn [(encrypt-vals [p]
              ;; TODO USe customer iv
              (map #(update % :value (partial p/encrypt v nil)) p))
            (encrypt-params [b]
              (map #(update % :parameters encrypt-vals) b))]
      (update-in req [:parameters :body] encrypt-params))))

(def create-param 
  (comp (c/entity-creator st/save-param c/default-id)
        assign-customer-id
        encrypt-one))

(def get-repo-params
  "Retrieves the parameters that are available for the given repository.  This depends
   on the parameter label filters and the repository labels."
  ;; TODO Decrypt
  (partial c/get-for-repo-by-label st/find-params (mapcat :parameters)))

(def update-params
  (comp (partial c/update-for-customer st/save-params)
        encrypt-all))
