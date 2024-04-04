(ns monkey.ci.events.zmq
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold
             [deferred :as md]
             [stream :as ms]]
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

(defn- remove-listener [listeners l]
  (let [after (remove (comp (partial = l) :orig) listeners)]
    (log/debug "Listeners before removing" l ":" (count listeners) ", after:" (count after))
    after))

(defn- wait-until-started
  "Blocks until the server has started by checking the state stream."
  [server]
  ;; FIXME Even with this startup check, it still happens the client is not registered for events
  ;; This is probably because the server socket is still connecting at that point.
  @(ms/take! (:state-stream server))
  server)

(defrecord ZeroMQEvents [server client listeners context]
  co/Lifecycle
  (start [this]
    ;; Start both client and server, if provided
    (cond-> this
      server (assoc :server (-> (co/start server)
                                (wait-until-started)))
      client (assoc :client (co/start client))))

  (stop [{:keys [client server] :as this}]
    (zc/close-all (remove nil? [client server]))
    (when context
      (log/debug "Closing context")
      (.close context))
    ;; Don't dissoc otherwise this record turns into a map
    (assoc this :client nil :server nil :context nil))

  p/EventReceiver
  (add-listener [recv ef l]
    (let [m (get-in recv [:client :matches-filter?])]
      (when (empty? @listeners)
        (log/debug "Adding first listener, registering for all events")
        (ze/register (:client recv) nil))
      (swap! listeners conj {:orig l
                             :listener (filtering-listener #(m % ef) l)}))
    recv)

  (remove-listener [recv ef l]
    ;; To avoid not receiving any events when unregistering a listener for which
    ;; another one has already registered a filter, we just register for all events
    ;; and unregister when no more listeners remain.
    (swap! listeners remove-listener l)
    (when (empty? @listeners)
      (log/debug "No more listeners remaining, unregistering from events")
      ;; TODO Look out for race conditions: when the last one is unregistered
      ;; and a new one is registered at the same time.
      (ze/unregister (:client recv) nil))
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
                      addresses
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
                    listeners
                    ctx)))
