(ns monkey.ci.events.jms
  "Uses JMS to connect to an event broker.  Can also starts its own broker 
   server, although this is mostly meant for development and testing purposes."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]
            [monkey.jms :as jms]
            [monkey.jms.artemis :as artemis]))

(defn- start-broker
  "Starts an embedded Artemis broker with AMQP connector"
  [port]
  (log/info "Starting AMQP broker at port" port)
  (artemis/start-broker {:broker-port port}))

(defn- stop-broker [b]
  (artemis/stop-broker b))

(defn- url->port [url]
  (or (some-> url
              (java.net.URI.)
              (.getPort))
      5672))

(defn- maybe-start-broker [{:keys [enabled url]}]
  (when enabled
    (start-broker (url->port url))))

(defn- make-producer [ctx {:keys [dest] :as conf}]
  (let [serializer (fn [ctx msg]
                     (jms/make-text-message ctx (pr-str msg)))]
    (jms/make-producer ctx dest {:serializer serializer})))

(defn filtering-listener [pred l]
  (fn [evt]
    (when (pred evt) (l evt))))

(defn- remove-listener [listeners l]
  (remove (comp (partial = l) :orig) listeners))

(defn- event-handler
  "Invoked when an event is received.  Dispatches event to all listeners."
  [listeners]
  (fn [evt]
    (log/debug "Event received:" evt)
    ;; It's important that no listeners block, otherwise the entire handler will stop working.
    (doseq [l @listeners]
      ((:listener l) evt))))

(defn- make-consumer [ctx {:keys [dest] :as conf} listeners]
  (jms/make-consumer ctx dest
                     (event-handler listeners)
                     {:deserializer (comp u/parse-edn-str jms/message->str)}))

(defrecord JmsEvents [config matches-event? listeners broker producer consumer]
  p/EventPoster
  (post-events [this events]
    (let [events (if (sequential? events) events [events])]
      (doseq [evt events]
        (producer evt))
      this))
  
  p/EventReceiver
  (add-listener [this ef l]
    (swap! listeners conj {:orig l
                           :listener (filtering-listener #(matches-event? % ef) l)})
    this)
  
  (remove-listener [this ef l]
    (swap! listeners remove-listener l)
    this)

  co/Lifecycle
  (start [this]
    (let [broker (maybe-start-broker (:server config))
          cc (:client config)
          ctx (jms/connect cc)]
      (-> this
          (mc/assoc-some :broker broker)
          (assoc :context ctx)
          (assoc :producer (make-producer ctx cc)
                 :consumer (make-consumer ctx cc listeners)))))

  (stop [{:keys [broker context producer consumer] :as this}]
    (when consumer
      (.close consumer))
    (jms/disconnect context)
    (when broker
      (.stop broker))
    (assoc this :broker nil :producer nil :consumer nil :context nil)))

(defn make-jms-events [config matches-event?]
  (->JmsEvents config matches-event? (atom []) nil nil nil))
