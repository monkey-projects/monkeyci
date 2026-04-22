(ns monkey.ci.web.events
  (:require [manifold
             [bus :as mb]
             [stream :as ms]]
            [monkey.ci.events.http :as eh]
            [ring.util.response :as rur]))

(defn stream-response [stream]
  (-> (rur/response stream)
      (rur/header "content-type" "text/event-stream")
      (rur/header "access-control-allow-origin" "*")
      ;; For nginx, set buffering to no.  This will disable buffering on Nginx proxy side.
      ;; See https://www.nginx.com/resources/wiki/start/topics/examples/x-accel/#x-accel-buffering
      (rur/header "x-accel-buffering" "no")
      (rur/header "cache-control" "no-cache")))

(defn bus-stream
  "Returns SSE response for events that are received from a manifold bus.
   The stream will contain all events the given types, for which `pred`
   returns `true`."
  [bus evt-types pred]
  (let [out (->> (ms/stream 1)
                 (eh/add-keepalive)
                 (ms/sliding-stream 10))]
    (doseq [t evt-types]
      (let [in (cond->> (mb/subscribe bus t)
                 pred (ms/filter pred)
                 true (ms/transform (map eh/->sse)))]
        (ms/connect in out {:upstream? true})))
    (stream-response out)))

(defn stream->sse
  "Returns SSE response for events that are received from a manifold bus.
   The stream will contain all events of the types for which `pred`
   returns `true`."
  [stream pred]
  (let [out (->> (ms/stream 1)
                 (eh/add-keepalive)
                 (ms/sliding-stream 10))]
    (let [in (cond->> stream
               pred (ms/filter pred)
               true (ms/transform (map eh/->sse)))]
      (ms/connect in out))
    ;; Make sure not to close the upstream here, otherwise other SSE listeners won't
    ;; receive anything anymore.
    (stream-response out)))
