(ns monkey.ci.web.api
  (:require [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def body (comp :body :parameters))

(defn- id-getter [id-key]
  (comp id-key :path :parameters))

(defn- entity-getter [get-id getter]
  (fn [req]
    (if-let [match (some-> (c/req->storage req)
                           (getter (get-id req)))]
      (rur/response match)
      (rur/not-found nil))))

(defn- entity-creator [saver]
  (fn [req]
    (let [c (-> (body req)
                (assoc :id (st/new-id)))
          st (c/req->storage req)]
      (when (saver st c)
        ;; TODO Return full url to the created entity
        (rur/created (:id c) c)))))

(defn- entity-updater [get-id getter saver]
  (fn [req]
    (let [st (c/req->storage req)]
      (if-let [match (getter st (get-id req))]
        (let [upd (merge match (body req))]
          (when (saver st upd)
            (rur/response upd)))
        ;; If no entity to update is found, return a 404.  Alternatively,
        ;; we could create it here instead and return a 201.  This could
        ;; be useful should we ever want to restore lost data.
        (rur/not-found nil)))))

(defn- make-entity-endpoints
  "Creates default api functions for the given entity using the configuration"
  [entity {:keys [get-id getter saver]}]
  (letfn [(make-ep [[p f]]
            (intern *ns* (symbol (str p entity)) f))]
    (->> {"get-" (entity-getter get-id getter)
          "create-" (entity-creator saver)
          "update-" (entity-updater get-id getter saver)}
         (map make-ep)
         (doall))))

(make-entity-endpoints "customer"
                       {:get-id (id-getter :customer-id)
                        :getter st/find-customer
                        :saver st/save-customer})

(make-entity-endpoints "project"
                       ;; The project is part of the customer, so combine the ids
                       {:get-id (comp (juxt :customer-id :project-id) :path :parameters)
                        :getter st/find-project
                        :saver st/save-project})

(make-entity-endpoints "repo"
                       ;; The repo is part of the customer/project, so combine the ids
                       {:get-id (comp (juxt :customer-id :project-id :repo-id) :path :parameters)
                        :getter st/find-repo
                        :saver st/save-repo})

(make-entity-endpoints "webhook"
                       {:get-id (id-getter :webhook-id)
                        :getter st/find-details-for-webhook
                        :saver st/save-webhook-details})

(def params-sid (comp (partial remove nil?)
                      (juxt :customer-id :project-id :repo-id)
                      :path
                      :parameters))

(defn get-params
  "Retrieves build parameters for the given location.  This could be at customer, 
   project or repo level.  For lower levels, the parameters for the higher levels
   are merged in."
  [req]
  (->> req
       (params-sid)
       (st/find-params (c/req->storage req))
       (rur/response)))

(defn update-params [req]
  (let [p (body req)]
    (when (st/save-params (c/req->storage req) (params-sid req) p)
      (rur/response p))))
