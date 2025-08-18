(ns monkey.ci.metrics.events
  "Event handlers that update metrics"
  (:require [monkey.ci.metrics
             [core :as c]
             [prometheus :as prom]]))

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

(def ctx->org-id (comp first :sid :event))
(def ctx->status (comp name :status :event))

(defn make-evt-counter
  "Creates a counter for given event type"
  [reg evt labels]
  (prom/make-counter (c/counter-id ((juxt namespace name) evt))
                     reg
                     {:labels labels}))

(defn- evt-route [reg type labels get-label-vals]
  [type
   [{:handler (constantly nil)
     :interceptors [(evt-counter (make-evt-counter reg type labels)
                                 get-label-vals)]}]])

(defn org-evt-route
  "Creates an event route that updates an event counter with org label"
  [reg type]
  (evt-route reg
             type
             ["org"]
             (comp vector ctx->org-id)))

(defn status-evt-route
  "Creates an event route that updates an event counter with org label
   and status"
  [reg type]
  (evt-route reg
             type
             ["org" "status"]
             (juxt ctx->org-id
                   ctx->status)))

(defn make-routes
  "Creates mailman routes that update metrics on received events"
  [reg]
  (concat
   (mapv (partial org-evt-route reg)
         [:build/triggered
          :build/queued
          :build/start
          :build/canceled
          :script/start
          :job/queued
          :job/start])
   (mapv (partial status-evt-route reg)
         [:build/end
          :script/end
          :job/executed
          :job/end])))
