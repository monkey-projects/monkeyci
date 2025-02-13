(ns monkey.ci.events.core
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as c]
             [protocols :as p]
             [runtime :as rt]
             [time :as t]]
            [monkey.ci.events
             [jms :as jms]
             [manifold :as manifold]]))

(defn post-events [e evts]
  (if e
    (p/post-events e evts)
    (log/warn "Unable to post, no events configured")))

(def add-listener p/add-listener)
(def remove-listener p/remove-listener)

(defn make-event
  "Creates a new event with required properties.  Additional properties are given as
   map keyvals, or as a single map."
  [type & props]
  (-> (if (= 1 (count props))
        (first props)
        (apply hash-map props))
      (assoc :type type
             :time (t/now))))

(defn invoke-listeners [filter-fn listeners events]
  ;; Find all listeners where the filter and event are matched by the filter-fn
  (doseq [[ef handlers] listeners]
    (->> events
         (filter #(filter-fn % ef))
         (mapcat (fn [evt]
                   (doseq [h handlers]
                     (h evt))))
         (doall))))

;; Simple in-memory implementation, useful for testing
(deftype SyncEvents [filter-fn listeners]
  p/EventPoster
  (post-events [this evt]
    (invoke-listeners filter-fn @listeners (if (sequential? evt) evt [evt]))
    this)
  
  p/EventReceiver
  (add-listener [this ef l]
    (swap! listeners update ef (fnil conj []) l)
    this)

  (remove-listener [this ef l]
    (swap! listeners update ef (partial remove (partial = l)))
    this))

(defn matches-event?
  "Matches events against event filters.  This checks event types and possible sid."
  [evt {:keys [types sid] :as ef}]
  ;; TODO Allow for more generic filter fn
  (letfn [(matches-type? [evt]
            (or (nil? types) (contains? types (:type evt))))
          (matches-sid? [evt]
            (or (nil? sid) (= sid (take (count sid) (:sid evt)))))]
    (or (nil? ef)
        ((every-pred matches-type? matches-sid?) evt))))

(defn make-sync-events [filter-fn]
  (->SyncEvents filter-fn (atom {})))

(defn ^:deprecated filter-type [t f]
  (fn [evt]
    (when (= t (:type evt))
      (f evt))))

(defn ^:deprecated no-dispatch
  "Wraps `f` so that it always returns `nil`, to avoid events being re-dispatched."
  [f]
  (fn [evt]
    (f evt)
    nil))

(defmulti make-events :type)

(defmethod make-events :sync [_]
  (make-sync-events matches-event?))

(defmethod make-events :jms [config]
  (jms/make-jms-events config matches-event?))

(defmethod make-events :manifold [_]
  (manifold/make-manifold-events matches-event?))

(defmethod rt/setup-runtime :events [conf _]
  ;; Can be removed once we no longer use the old runtime in cli commands
  (when-let [ec (:events conf)]
    (make-events ec)))

(defn wrapped
  "Returns a new function that wraps `f` and posts an event before 
   and after.  The `before` fn just receives the same arguments as 
   `f`.  The `after` fn one more, the return value of `f`.  The first 
   argument is assumed to be the runtime, which is used to get the 
   event poster.  The return values of `before` and `after` are posted 
   as events. Returns the return value of calling `f`.

   Any of the event generating functions can be `nil`, in which case
   it will be ignored."
  [f before after & [error]]
  (letfn [(maybe-post [f [rt :as args] & extras]
            (when f
              (when-let [e (apply f (concat args extras))]
                (rt/post-events rt e))))
          (inv [args]
           (let [r (apply f args)]
             (maybe-post after args r)
             r))]
    (fn [& args]
      (maybe-post before args)
      (if error
        (try
          (inv args)
          (catch Exception ex
            ;; Post event and re-throw
            (maybe-post error args ex)
            (throw ex)))
        (inv args)))))

(defn wait-for-event
  "Utility fn that registers a listener using an event filter and invokes the handler when 
   one has been received.  Returns a deferred that realizes with the received event.  An 
   additional predicate can do extra filtering if it's not supported by the event filter."
  [events ef & [pred]]
  (log/debug "Waiting for event to arrive that matches filter:" ef)
  (let [r (md/deferred)
        l (fn [evt]
            (when (or (nil? pred) (pred evt))
              (log/debug "Matching event has arrived for filter" ef ":" evt)
              (md/success! r evt)))
        unregister (fn []
                     (remove-listener events ef l))]
    (add-listener events ef l)
    ;; Make sure to unregister the listener in any case
    (md/finally r unregister)))

;;; Utility functions for building events

(defn make-result [status exit-code msg]
  {:status status
   :exit exit-code
   :message msg})

(defn exception-result [ex]
  (-> (make-result :error 1 (ex-message ex))
      (assoc :exception ex)))

(defn set-result [evt r]
  (assoc evt :result r))

(def result :result)
(def result-exit (comp :exit result))
