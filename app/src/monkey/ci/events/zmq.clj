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

(defn filtering-listener [pred l]
  (fn [evt]
    (when (pred evt) (l evt))))

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
    (let [m (get-in recv [:client :matches-filter?])]
      (ze/register (:client recv) ef)
      (swap! listeners conj {:orig l
                             :listener (filtering-listener #(m % ef) l)}))
    recv)

  (remove-listener [recv ef l]
    (ze/unregister (:client recv) ef)
    (swap! listeners (partial remove (comp (partial = l) :orig)))
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
      ((:listener l) evt))))

(defn- make-client [{:keys [address context linger]} filter-fn listeners]
  (-> (ze/broker-client (or context (make-context))
                        address
                        (event-handler listeners)
                        ;; Let component start it
                        {:autostart? false
                         :close-context? (nil? context)
                         :linger (or linger 5000)})
      (assoc :matches-filter? filter-fn)))

(defn- make-server [{:keys [addresses context enabled linger]
                     :or {enabled false linger 5000}}
                    filter-fn]
  (when enabled
    (ze/broker-server (or context (make-context))
                      (first addresses) ; Only one address supported for now
                      {:autostart? false  ; Let component start it
                       :matches-filter? filter-fn
                       :linger linger
                       :close-context? (nil? context)})))

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
                    (make-client client filter-fn listeners)
                    listeners)))
