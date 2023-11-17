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
         (log/trace "Event:" evt)
         evt)))

(defn validator
  "Validates events.  Logs a warning if an event does not conform to spec, but
   does not block the event from being processed."
  []
  (map (fn [evt]
         (when-not (s/valid? :evt/event evt)
           (log/warn "Event is not according to spec:" evt))
         evt)))

(defn make-channel
  "Creates default event processing channel, that can then be used to make a bus."
  []
  (ca/chan 10 (comp (event-logger)
                    (validator))))

(defn make-bus
  ([ch]
   ;; Set up a mult on the channel.  This allows us to tap all messages should we want to.
   (let [m (ca/mult ch)
         t (ca/chan)]
     (ca/tap m t)
     {:channel ch
      :mult m
      :pub (ca/pub t :type)}))
  ([]
   (make-bus (make-channel))))

(defn close-bus [{:keys [channel]}]
  (when channel
    (ca/close! channel)))

(defn bus? [x]
  (s/valid? :evt/event-bus x))

(def channel :channel)
(def mult :mult)
(def pub :pub)

(defn register-handler
  "Registers a handler for events of the given type.  The handler should
   not perform any blocking operations.  In that case, it should start a
   thread and park.  The bus is always added as an extra property in the
   event."
  [bus type handler]
  (log/debug "Registering handler for" type)
  (let [ch (ca/chan)]
    (ca/sub (:pub bus) type ch)
    {:channel ch
     :type type
     :handler handler
     :loop (ca/go-loop [e (<! ch)]
             (when e
               (try
                 (handler (assoc e :bus bus))
                 (catch Exception ex
                   (go (>! (:channel bus) {:type :error
                                           :handler handler
                                           :event e
                                           :exception ex}))))
               (recur (<! ch))))}))

(defn register-pipeline
  "Registers a pipeline handler in the bus.  This pipeline will subscribe to all
   events of given type, pass them through the transducer `tx` and then send the
   resulting event back to the bus.  Returns a handler object that contains the
   intermediate channel for the pipeline.  It's imperative that the transducer
   changes the event type, otherwise you'll have a feedback loop."
  [bus type tx]
  (log/debug "Registering pipeline handler for" type)
  (let [ch (ca/chan)]
    (ca/sub (pub bus) type ch)
    (ca/pipeline 1 (channel bus) (comp (map #(assoc % :bus bus)) tx) ch)
    {:type type
     :channel ch
     :tx tx}))

(defn unregister-handler
  "Unregisters the handler (as returned from `register-handler`) from the
   bus.  It will no longer receive events.  Returns the bus."
  [bus handler]
  (ca/unsub (:pub bus) (:type handler) (:channel handler))
  bus)

(defn register-ns-handlers
  "Finds all public objects in the ns, and registers all that have the appropriate
   metadata.  Returns all registered handlers.  Everything that has an `:event/handles`
   or an `:event/tx` metadata will be registered.  The former will be registered
   as a regular handler for the given type, the latter as a pipeline."
  [bus ns]
  (let [event-handler? (some-fn :event/handles :event/tx)
        register (fn [obj]
                   (let [{:keys [event/handles event/tx]} (meta obj)
                         f (var-get obj)]
                     (if handles
                       (register-handler bus handles f)
                       (register-pipeline bus tx f))))]
    (->> (ns-publics ns)
         (vals)
         (filter (comp event-handler? meta))
         (map register))))

(defn handler? [x]
  (true? (s/valid? :evt/event-handler x)))

(defn post-event
  "Asynchronously posts the event to the bus.  Returns a channel that will hold
   `true` once the event has been posted."
  [bus evt]
  (log/trace "Posting event:" evt)
  (ca/put! (:channel bus) (assoc evt :time (System/currentTimeMillis))))

(defn wait-for
  "Returns a channel that will hold the first match for the given
   type and transducer `tx`."
  [bus type tx]
  (let [r (ca/promise-chan tx)]
    (ca/sub (:pub bus) type r)
    r))

(defn do-and-wait
  "Invokes `f`, which is assumed to publish an event, and waits for the first event of
   the given type that matches transducer `tx`.  Returns the waiter channel, as in
   `wait-for`."
  [f bus type tx]
  ;; Start waiting before actualy executing because otherwise we may miss the event.
  (let [w (wait-for bus type tx)]
    (f)
    w))

(defn with-ctx
  "Creates a new event that adds the context, and puts the event in the `:event` key.
   Useful for enrichting events."
  [ctx evt]
  (assoc ctx :event evt))

(defn build-completed-evt
  "Creates a build completed event"
  [build exit-code & keyvals]
  (cond-> {:type :build/completed
           :build build
           :exit exit-code
           :result (if (zero? exit-code) :success :error)}
    (not-empty keyvals) (merge (apply hash-map keyvals))))

(defn then-fire
  "When the contexts contains a bus, invokes `f` with `v` that is assumed to
   return an event that is fired.  Returns `v`."
  [v {:keys [event-bus]} f]
  (when event-bus
    (when-let [evt (f v)]
      (post-event event-bus evt)))
  v)
