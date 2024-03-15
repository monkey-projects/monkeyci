(ns monkey.ci.events.zmq
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci.protocols :as p]
            [monkey.zmq
             [common :as zc]
             [events :as ze]]
            [zeromq.zmq :as z]))

(defmulti make-zeromq-events :mode)

(defn make-context []
  (z/context 1))

(defn- post-internal [poster events]
  (if (sequential? events)
    (doseq [evt events]
      (poster evt))
    (poster events)))

(defn- dispatch-event
  "Dispatches an incoming event to all listeners"
  [poster listeners evt]
  (log/debug "Dispatching event:" evt)
  (doseq [l @listeners]
    ;; Post back return values
    (some->> (l evt)
             (post-internal poster))))

(defrecord EventServer [ctx endpoint listeners]
  p/EventPoster
  (post-events [this events]
    (post-internal (:poster this) events)
    this)

  p/EventReceiver
  (add-listener [this l]
    (swap! listeners conj l)
    this)
  
  (remove-listener [this l]
    (swap! listeners (comp vec (partial remove (partial = l))))
    this)

  co/Lifecycle
  (start [this]
    (log/info "Starting event servers for endpoint:" endpoint)
    (let [internal-addr (str "inproc://internal-" (random-uuid))
          poster (ze/event-poster ctx internal-addr)
          dispatcher (partial dispatch-event poster listeners)
          ;; Internal server is used for posting events
          internal (ze/event-server ctx internal-addr dispatcher)]
      (assoc this
             :closeables [(ze/event-server ctx endpoint dispatcher)
                          internal
                          poster]
             :poster poster)))

  (stop [{:keys [closeables] :as this}]
    (when closeables
      (log/debug "Shutting down" (count closeables) "servers and clients")
      (zc/close-all closeables))
    (dissoc this :closeables :poster)))

(defmethod make-zeromq-events :server [{:keys [context endpoint]}]
  (->EventServer (or context (make-context)) endpoint (atom [])))

(defrecord EventClient [ctx endpoint]
  p/EventPoster
  (post-events [this events]
    (post-internal (:poster this) events)
    this)

  co/Lifecycle
  (start [this]
    (log/info "Starting event client for endpoint:" endpoint)
    (let [poster (ze/event-poster ctx endpoint)]
      (assoc this :poster poster)))

  (stop [{:keys [poster] :as this}]
    (when poster
      (log/debug "Shutting down event poster client")
      (.close poster))
    (dissoc this :poster)))

(defmethod make-zeromq-events :client [{:keys [context endpoint]}]
  (->EventClient (or context (make-context)) endpoint))
