(ns monkey.ci.web.api
  (:require [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def body (comp :body :parameters))
(def cust-id (comp :customer-id :path :parameters))

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
        (rur/created (:id c))))))

(defn- entity-updater [get-id getter saver]
  (fn [req]
    (let [st (c/req->storage req)]
      (if-let [match (getter st (get-id req))]
        (let [upd (merge match (body req))]
          (when (saver st upd)
            (rur/response upd)))
        (rur/not-found nil)))))

(def get-customer (entity-getter cust-id st/find-customer))
(def create-customer (entity-creator st/save-customer))
(def update-customer (entity-updater cust-id st/find-customer st/save-customer))
