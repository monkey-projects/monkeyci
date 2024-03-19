(ns monkey.ci.events.zmq
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci.protocols :as p]
            [monkey.zmq
             [common :as zc]
             [events :as ze]]
            [zeromq.zmq :as z]))

;; TODO Add security

(defn make-context []
  (z/context 1))

(defn- post-internal [poster events]
  (if (sequential? events)
    (->> events
         (map poster)
         (apply md/zip))
    (poster events)))

(defrecord ZeroMQEvents [server client listeners]
  co/Lifecycle
  (start [this]
    ;; Start both client and server, if provided
    (cond-> this
      server (assoc :server (co/start server))
      client (assoc :client (co/start client))))

  (stop [{:keys [client server] :as this}]
    (zc/close-all (remove nil? [client server]))
    (assoc this :client nil :server nil))

  p/EventReceiver
  (add-listener [recv l]
    ;; TODO Specify filter
    (ze/register (:client recv) nil)
    (swap! listeners conj l)
    recv)

  (remove-listener [recv l]
    (swap! listeners (partial remove (partial = l)))
    recv)

  p/EventPoster
  (post-events [{:keys [client]} evt]
    (when client
      (post-internal client evt))))

(defn matches-filter?
  "Used by the event broker to check if an event matches a registered filter."
  [evt ef]
  ;; TODO Implement
  true)

(defn- event-handler
  "Invoked when an event is received.  Dispatches event to all listeners."
  [listeners]
  (fn [evt]
    (doseq [l @listeners]
      (l evt))))

(defn- make-client [{:keys [address context]} listeners]
  (ze/broker-client (or context (make-context))
                    address
                    (event-handler listeners)
                    ;; Let component start it
                    {:autostart? false}))

(defn- make-server [{:keys [addresses context enabled] :or {enabled false}}]
  (when enabled
    (ze/broker-server (or context (make-context))
                      (first addresses) ; Only one address supported for now
                      {:autostart? false  ; Let component start it
                       :matches-filter? matches-filter?})))

(defn make-zeromq-events
  "Creates zeromq events component.  Depending on configuration, it can contain both
   a client and a server.  The client connects to the server in order to post events,
   so the client functions as the event poster.  Both need to be started to work."
  [{:keys [client server]}]
  (let [listeners (atom [])]
    (->ZeroMQEvents (make-server server)
                    (make-client client listeners)
                    listeners)))
