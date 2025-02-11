(ns monkey.ci.events.http
  "Functions for doing events over http"
  (:require [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.edn :as edn]
            [monkey.ci.events.core :as ec]
            [ring.util.response :as rur]))

(def evt-prefix "data: ")

(defn stream-response [stream]
  (-> (rur/response stream)
      (rur/header "content-type" "text/event-stream")
      (rur/header "access-control-allow-origin" "*")
      ;; For nginx, set buffering to no.  This will disable buffering on Nginx proxy side.
      ;; See https://www.nginx.com/resources/wiki/start/topics/examples/x-accel/#x-accel-buffering
      (rur/header "x-accel-buffering" "no")
      (rur/header "cache-control" "no-cache")))

(defn event-stream-listener
  "Creates an event stream listener function.  When invoked with an event, puts it
   on the stream."
  [stream]
  (let [make-reply (fn [evt]
                     ;; Format according to sse specs, with double newline at the end
                     (str evt-prefix (pr-str evt) "\n\n"))
        listener (fn [evt]
                   (ms/put! stream (make-reply evt)))]
    ;; Set up a keepalive, which pings the client periodically to keep the connection open.
    ;; The initial ping will make the browser "open" the connection.  The timeout must always
    ;; be lower than the read timeout of the client, or any intermediate proxy server.
    ;; TODO Ideally we should not send a ping if another event has been sent more recently.
    ;; TODO Make the ping timeout configurable
    (-> (ms/periodically 30000 0 (constantly (make-reply {:type :ping})))
        (ms/connect stream {:upstream? true})
        (md/catch (fn [ex]
                    (log/warn "Unable to send ping:" (ex-message ex)))))
    listener))

(defn event-stream
  "Sets up an event stream for the specified filter."
  [events evt-filter]
  (let [stream (ms/stream 1)
        listener (event-stream-listener stream)]
    (log/info "Opening event stream for filter:" evt-filter)
    (ms/on-drained stream
                   (fn []
                     (log/info "Closing event stream for filter" evt-filter)
                     (ec/remove-listener events evt-filter listener)))
    ;; Only process events matching the filter
    (ec/add-listener events evt-filter listener)
    (stream-response stream)))

(defn mailman-stream
  "Sets up an event stream using a mailman listener.  It consumes events from the
   configured topic and returns them as an SSE stream."
  [broker]
  ;; TODO
  )

(defn parse-event-line [line]
  (when (and line (.startsWith line evt-prefix))
    (edn/edn-> (subs line (count evt-prefix)))))
