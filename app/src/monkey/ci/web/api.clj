(ns monkey.ci.web.api
  (:require [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def body (comp :body :parameters))

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

(def cust-id (comp :customer-id :path :parameters))

(def get-customer (entity-getter cust-id st/find-customer))
(def create-customer (entity-creator st/save-customer))
(def update-customer (entity-updater cust-id st/find-customer st/save-customer))

(def webhook-id (comp :webhook-id :path :parameters))

(def get-webhook (entity-getter webhook-id st/find-details-for-webhook))
(def create-webhook (entity-creator st/save-webhook-details))
(def update-webhook (entity-updater webhook-id st/find-details-for-webhook st/save-webhook-details))
