(ns monkey.ci.events
  "Core eventing namespace.  Provides functionality for working with application
   events."
  (:require [clojure.core.async :as ca :refer [go <! >!]]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [monkey.ci.spec :as spec]))

(defn event-logger
  "Transducer that simply logs events.  Useful for debugging."
  []
  (map (fn [evt]
         (log/debug "Event:" evt)
         evt)))

(defn validator
  "Validates events.  Logs a warning if an event does not conform to spec, but
   does not block the event from being processed."
  []
  (map (fn [evt]
         (when-not (s/valid? ::spec/event evt)
           (log/warn "Event is not according to spec:" evt))
         evt)))

(defn make-channel
  "Creates default event processing channel, that can then be used to make a bus."
  []
  (ca/chan 10 (comp (event-logger)
                    (validator))))

(defn make-bus
  ([ch]
   {:channel ch
    :pub (ca/pub ch :type)})
  ([]
   (make-bus (make-channel))))

(defn close-bus [{:keys [channel]}]
  (when channel
    (ca/close! channel)))

(defn bus? [x]
  (s/valid? ::spec/event-bus x))

(def channel :channel)

(defn register-handler
  "Registers a handler for events of the given type."
  [bus type handler]
  (let [ch (ca/chan)]
    (ca/sub (:pub bus) type ch)
    {:channel ch
     :handler handler
     :loop (ca/go-loop [e (<! ch)]
             (when e
               (try
                 (handler e)
                 (catch Exception ex
                   (go (>! (:channel bus) {:type :error
                                           :handler handler
                                           :event e
                                           :exception ex}))))
               (recur (<! ch))))}))

(defn handler? [x]
  (s/valid? ::spec/event-handler x))

(defn post-event
  "Asynchronously posts the event to the bus.  Returns a channel that will hold
   `true` once the event has been posted."
  [bus evt]
  (ca/put! (:channel bus) evt))

(defn wait-for
  "Returns a channel that will hold the first match for the given
   type and transducer `tx`."
  [bus type tx]
  (let [r (ca/promise-chan tx)]
    (ca/sub (:pub bus) type r)
    r))
