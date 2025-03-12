(ns monkey.ci.metrics.events
  "Event handlers that update metrics"
  (:require [monkey.ci.metrics.core :as c]
            [monkey.ci.prometheus :as prom]))

(def get-counter ::counter)

(defn set-counter [ctx c]
  (assoc ctx ::counter c))

(defn evt-counter
  "Interceptor that increases a metrics counter on enter with label values as returned
   by `get-label-vals` on the context."
  [counter get-label-vals]
  {:name ::event-counter
   :enter (fn [ctx]
            (->> (prom/counter-inc counter 1 (get-label-vals ctx))
                 (set-counter ctx)))})

(def ctx->customer-id (comp vector first :sid :event))

(defn make-evt-counter
  "Creates a counter for given event type"
  [reg evt]
  (prom/make-counter (c/counter-id ((juxt namespace name) evt))
                     reg
                     {:labels ["customer"]}))

(defn cust-evt-handler
  "Creates an event handler that updates an event counter with customer label"
  [reg type]
  [type
   [{:handler (constantly nil)
     :interceptors [(evt-counter (make-evt-counter reg type)
                                 ctx->customer-id)]}]])

(defn make-routes
  "Creates mailman routes that update metrics on received events"
  [reg]
  (->> [:build/triggered
        :build/queued
        :build/start
        :build/end]
       (mapv (partial cust-evt-handler reg))))
