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
        (rur/not-found nil)))))

(defn- make-entity-endpoints
  "Creates default api functions for the given entity using the configuration"
  [entity {:keys [get-id getter saver]}]
  (intern *ns*
          (symbol (str "get-" entity))
          (entity-getter get-id getter))
  (intern *ns*
          (symbol (str "create-" entity))
          (entity-creator saver))
  (intern *ns*
          (symbol (str "update-" entity))
          (entity-updater get-id getter saver)))

(make-entity-endpoints "customer"
                       {:get-id (id-getter :customer-id)
                        :getter st/find-customer
                        :saver st/save-customer})

(make-entity-endpoints "project"
                       ;; The project is part of the customer, so combine the ids
                       {:get-id (comp (juxt :customer-id :project-id) :path :parameters)
                        :getter st/find-project
                        :saver st/save-project})

(make-entity-endpoints "webhook"
                       {:get-id (id-getter :webhook-id)
                        :getter st/find-details-for-webhook
                        :saver st/save-webhook-details})
