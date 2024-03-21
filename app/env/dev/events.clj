(ns events
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [monkey.ci.events.core :as ec]))

(defn trace-events
  "Connects to zmq events endpoint and puts all events on a stream."
  [addr]
  (let [stream (ms/stream 100)
        events (-> (ec/make-events {:events
                                    {:type :zmq
                                     :client {:address addr}}})
                   (co/start))]
    (ec/add-listener events nil (partial ms/put! stream))
    (ms/on-closed stream
                  (fn []
                    (println "Stream closed, closing client")
                    (co/stop events)))
    (println "Started capturing all events")
    stream))

(defn log-events
  "Consumes event stream and logs the events.  A human readable description
   is printed to repl, the full event to log."
  [stream]
  (letfn [(log-evt [evt]
            (println "Event:" (:type evt)
                     "-" (some-> evt :time (java.time.Instant/ofEpochMilli))
                     "-" (:message evt))
            (log/info "Event:" evt))]
    (ms/consume log-evt stream)))
