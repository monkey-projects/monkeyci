(ns monkey.ci.events.http
  "Functions for doing events over http"
  (:require [clojure.tools.logging :as log]
            [manifold
             [bus :as mb]
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.edn :as edn]
            [monkey.ci.events.core :as ec]
            [monkey.mailman.core :as mmc]
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

(defn- ->sse [evt]
  (log/trace "Converting to SSE:" evt)
  ;; Format according to sse specs, with double newline at the end
  (str evt-prefix (pr-str evt) "\n\n"))

(defn- add-keepalive [stream]
  ;; Set up a keepalive, which pings the client periodically to keep the connection open.
  ;; The initial ping will make the browser "open" the connection.  The timeout must always
  ;; be lower than the read timeout of the client, or any intermediate proxy server.
  ;; TODO Ideally we should not send a ping if another event has been sent more recently.
  ;; TODO Make the ping timeout configurable
  (-> (ms/periodically 30000 0 (constantly (->sse {:type :ping})))
      (ms/connect stream {:upstream? true})
      (md/catch (fn [ex]
                  (log/warn "Unable to send ping:" (ex-message ex)))))
  stream)

(defn event-stream-listener
  "Creates an event stream listener function.  When invoked with an event, puts it
   on the stream."
  [stream]
  (fn [evt]
    (ms/put! stream (->sse evt))))

(defn mailman-stream
  "Sets up an event stream using a mailman listener.  It consumes events from the
   configured topic and returns them as an SSE stream.  The predicate is applied
   to the stream events before they are sent to the client."
  [broker pred]
  ;; Set up a listener.  We could also register a single listener at startup
  ;; using the components, and pipe the events to a manifold bus.  Then we could
  ;; consume the bus here.
  (let [stream (-> (ms/stream 1)
                   (add-keepalive))
        handler (event-stream-listener stream)
        l (atom nil)]
    (log/debug "Opening event stream")
    (ms/on-drained stream
                   (fn []
                     (log/debug "Closing event stream")
                     (when @l
                       (mmc/unregister-listener @l))))
    ;; FIXME Not portable, listener format depends on broker
    (reset! l (mmc/add-listener broker (fn [evt]
                                         (when (or (not pred) (pred evt))
                                           (handler evt)))))
    (stream-response stream)))

(defn bus-stream
  "Returns SSE response for events that are received from a manifold bus.
   The stream will contain all events the given types, for which `pred`
   returns `true`."
  [bus evt-types pred]
  (let [out (->> (ms/stream 1)
                 (add-keepalive)
                 (ms/sliding-stream 10))]
    (doseq [t evt-types]
      (let [in (cond->> (mb/subscribe bus t)
                 pred (ms/filter pred)
                 true (ms/transform (map ->sse)))]
        (ms/connect in out {:upstream? true})))
    (stream-response out)))

(defn stream->sse
  "Returns SSE response for events that are received from a manifold bus.
   The stream will contain all events the given types, for which `pred`
   returns `true`."
  [stream pred]
  (let [out (->> (ms/stream 1)
                 (add-keepalive)
                 (ms/sliding-stream 10))]
    (let [in (cond->> stream
               pred (ms/filter pred)
               true (ms/transform (map ->sse)))]
      (ms/connect in out))
    ;; Make sure not to close the upstream here, otherwise other SSE listeners won't
    ;; receive anything anymore.
    (stream-response out)))

(defn parse-event-line [line]
  (when (and line (.startsWith line evt-prefix))
    (edn/edn-> (subs line (count evt-prefix)))))
