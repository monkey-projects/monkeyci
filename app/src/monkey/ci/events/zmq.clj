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
  (add-listener [recv ef l]
    (ze/register (:client recv) ef)
    (swap! listeners conj l)
    recv)

  (remove-listener [recv ef l]
    (ze/unregister (:client recv) ef)
    (swap! listeners (partial remove (partial = l)))
    recv)

  p/EventPoster
  (post-events [{:keys [client]} evt]
    (when client
      (post-internal client evt))))

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

(defn- make-server [{:keys [addresses context enabled] :or {enabled false}} filter-fn]
  (when enabled
    (ze/broker-server (or context (make-context))
                      (first addresses) ; Only one address supported for now
                      {:autostart? false  ; Let component start it
                       :matches-filter? filter-fn})))

(defn- reuse-context?
  "Checks if we should reuse context.  This is necessary if we use inproc protocol."
  [conf]
  (some-> conf
          (get-in [:client :address])
          (.startsWith "inproc://")))

(defn make-zeromq-events
  "Creates zeromq events component.  Depending on configuration, it can contain both
   a client and a server.  The client connects to the server in order to post events,
   so the client functions as the event poster.  Both need to be started to work."
  [conf filter-fn]
  (let [listeners (atom [])
        ;; When using inproc protocol, we need to reuse the context
        ctx (when (reuse-context? conf) (make-context))
        {:keys [client server]} (cond-> conf
                                  ctx (-> (assoc-in [:client :context] ctx)
                                          (assoc-in [:server :context] ctx)))]
    (->ZeroMQEvents (make-server server filter-fn)
                    (make-client client listeners)
                    listeners)))
