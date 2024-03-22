(ns monkey.ci.events.core
  (:require [manifold.deferred :as md]
            [monkey.ci
             [config :as c]
             [protocols :as p]
             [runtime :as rt]]
            [monkey.ci.events
             [manifold :as manifold]
             [zmq :as zmq]]))

(def post-events p/post-events)
(def add-listener p/add-listener)
(def remove-listener p/remove-listener)

(defn make-event [e]
  (assoc e :timestamp (System/currentTimeMillis)))

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
  (letfn [(matches-type? [evt]
            (or (nil? types) (contains? types (:type evt))))
          (matches-sid? [evt]
            (or (nil? sid) (= sid (take (count sid) (:sid evt)))))]
    ;; TODO Add sid check
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

(defmethod c/normalize-key :events [k conf]
  (update conf k (comp #(c/group-keys % :client)
                       #(c/group-keys % :server)
                       c/keywordize-type)))

(defmulti make-events (comp :type :events))

(defmethod make-events :sync [_]
  (make-sync-events matches-event?))

(defmethod make-events :manifold [_]
  (manifold/make-manifold-events matches-event?))

(defmethod make-events :zmq [config]
  (zmq/make-zeromq-events (:events config) matches-event?))

(defmethod rt/setup-runtime :events [conf _]
  (when (:events conf)
    (make-events conf)))

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
  "Utility fn that registers using an event filter and invokes the handler when one has
   been received.  Returns a deferred that realizes with the received event.  An additional
   predicate can do extra filtering if it's not supported by the event filter."
  [events ef & [pred]]
  (let [r (md/deferred)
        l (fn [evt]
            (when (or (nil? pred) (pred evt))
              (md/success! r evt)))
        unregister (fn [_]
                     (remove-listener events ef l))]
    ;; Make sure to unregister the listener in any case
    (md/on-realized r unregister unregister)
    (add-listener events ef l)
    r))

