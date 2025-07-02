(ns monkey.ci.web.api.params
  "Api functions for managing build parameters"
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web
             [common :as c]
             [crypto :as cr]]))

(defn- encrypt-one [req]
  (let [encrypter (cr/encrypter req)]
    (letfn [(encrypt-vals [p]
              (map #(update % :value encrypter) p))]
      (update-in req [:parameters :body :parameters] encrypt-vals))))

(defn- encrypt-all [req]
  (let [encrypter (cr/encrypter req)]
    (letfn [(encrypt-vals [p]
              (map #(update % :value encrypter) p))
            (encrypt-params [b]
              (map #(update % :parameters encrypt-vals) b))]
      (update-in req [:parameters :body] encrypt-params))))

(defn- decrypt
  "Decryps all parameter values using the vault from the request"
  [req params]
  (let [decrypter (cr/decrypter req)]
    (letfn [(decrypt-vals [p]
              (mapv #(update % :value decrypter) p))]
      (->> params
           (map #(update % :parameters decrypt-vals))))))

(defn- decrypt-one [req param]
  (->> (decrypt req [param])
       first))

(defn get-org-params
  "Retrieves all parameters configured on the org.  This is for administration purposes."
  [req]
  (c/get-list-for-org (comp (partial decrypt req) c/drop-ids st/find-params) req))

(defn- get-param-id [req]
  (st/params-sid (c/org-id req)
                 (get-in req [:parameters :path :param-id])))

(c/make-entity-endpoints
 "param"
 {:get-id get-param-id
  :deleter st/delete-param})

(defn- assign-org-id [req]
  (update-in req [:parameters :body] assoc :org-id (c/org-id req)))

(defn get-param [req]
  (let [getter (c/entity-getter get-param-id (comp (partial decrypt-one req)
                                                   st/find-param))]
    (getter req)))

(def create-param 
  (comp (c/entity-creator st/save-param c/default-id)
        assign-org-id
        encrypt-one))

(def update-param 
  (comp (c/entity-updater get-param-id st/find-param st/save-param)
        encrypt-one))

(defn get-repo-params
  "Retrieves the parameters that are available for the given repository.  This depends
   on the parameter label filters and the repository labels."
  [req]
  (c/get-for-repo-by-label (comp (partial decrypt req) st/find-params) (mapcat :parameters) req))

(def update-params
  (comp (partial c/update-for-org st/save-params)
        encrypt-all))
