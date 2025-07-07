(ns monkey.ci.web.api.params
  "Api functions for managing build parameters"
  (:require [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.web
             [common :as c]
             [crypto :as cr]]))

(defn- req->param-id [req]
  (get-in req [:parameters :path :param-id]))

(defn- encrypt-one
  ([req id]
   (let [encrypter (cr/encrypter req)]
     (letfn [(encrypt-vals [p]
               (map (fn [v]
                      (update v :value #(encrypter % (c/org-id req) id)))
                    p))]
       (update-in req [:parameters :body :parameters] encrypt-vals))))
  ([req]
   (encrypt-one req (req->param-id req))))

(defn- encrypt-all [req]
  (let [encrypter (cr/encrypter req)]
    (letfn [(encrypt-vals [params-id p]
              (map (fn [v]
                     (update v :value #(encrypter % (c/org-id req) params-id)))
                   p))
            (encrypt-params [b]
              ;; FIXME This does not work for update if id is not specified in the body
              (map (fn [{:keys [id] :as p}]
                     (update p :parameters (partial encrypt-vals id)))
                   b))]
      (update-in req [:parameters :body] encrypt-params))))

(defn- decrypt
  "Decryps all parameter values using the vault from the request"
  [req params]
  (let [decrypter (cr/decrypter req)]
    (letfn [(decrypt-vals [d p]
              (mapv #(update % :value d) p))
            (decrypt-param [{:keys [id] :as p}]
              (update p :parameters (partial decrypt-vals #(decrypter % (c/org-id req) id))))]
      (map decrypt-param params))))

(defn- decrypt-one [req param]
  (->> (decrypt req [param])
       first))

(defn get-org-params
  "Retrieves all parameters configured on the org.  This is for administration purposes."
  [req]
  (c/get-list-for-org (comp (partial decrypt req) c/drop-ids st/find-params) req))

(defn- get-param-id [req]
  (st/params-sid (c/org-id req)
                 (req->param-id req)))

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

(defn create-param [req]
  (let [id (cuid/random-cuid)
        ec (c/entity-creator st/save-param (constantly id))]
    (-> req
        (encrypt-one id)
        (assign-org-id)
        (ec))))

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
