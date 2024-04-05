(ns monkey.ci.events.jms
  "Uses JMS (with bowerick) to connect to an event broker.  Can also
   starts its own broker server, although this is mostly meant for
   development and testing purposes."
  (:require [bowerick.jms :as jms]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]))

(def jms-ns 'bowerick.jms)

(defmacro with-logs [& body]
  ;; Bowerick uses println to log, so we need to redirect it here.
  ;; Don't use this with bodies that use regular logs because when
  ;; that prints to stdout, it will stack overflow.
  ;; Also, for some strange reason, it prints regular logs to stderr and
  ;; errors to stdout *facepalm*...
  `(log/with-logs [jms-ns :debug :debug] ~@body))

(defn- maybe-start-broker [{:keys [enabled url]}]
  (when enabled
    (with-logs
      (jms/start-broker url))))

(defn- wrap-creds [{:keys [username password]} h]
  (if (and username password)
    (binding [jms/*user-name* username
              jms/*user-password* password]
      (h))
    (h)))

(defn- make-producer [{:keys [url dest] :as conf}]
  ;; TODO Auto reconnect
  (with-logs
    (wrap-creds
     conf
     #(jms/create-producer url dest 1 (comp (memfn getBytes) pr-str)))))

(defn filtering-listener [pred l]
  (fn [evt]
    (when (pred evt) (l evt))))

(defn- remove-listener [listeners l]
  (remove (comp (partial = l) :orig) listeners))

(defn- event-handler
  "Invoked when an event is received.  Dispatches event to all listeners."
  [listeners]
  (fn [evt]
    (doseq [l @listeners]
      ((:listener l) evt))))

(defn- parse-edn [buf]
  (with-open [r (io/reader buf)]
    (u/parse-edn r)))

(defn- make-consumer [{:keys [url dest] :as conf} listeners]
  ;; TODO Auto reconnect
  (with-logs
    (wrap-creds
     conf
     #(jms/create-consumer url dest (event-handler listeners) 1 parse-edn))))

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
    (-> this
        (mc/assoc-some :broker (maybe-start-broker (:server config)))
        (assoc :producer (make-producer (:client config))
               :consumer (make-consumer (:client config) listeners))))

  (stop [{:keys [broker producer consumer] :as this}]
    (with-logs
      (when producer
        (jms/close producer))
      (when consumer
        (jms/close consumer))
      (when broker
        (jms/stop broker)))
    (assoc this :broker nil :producer nil :consumer nil)))

(defn make-jms-events [config matches-event?]
  (->JmsEvents config matches-event? (atom []) nil nil nil))
