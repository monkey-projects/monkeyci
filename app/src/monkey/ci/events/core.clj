(ns monkey.ci.events.core
  (:require [monkey.ci
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

(defn post-one [listeners evt]
  (->> listeners
       (mapcat (fn [l]
                 (map l evt)))
       (flatten)
       (remove nil?)))

;; Simple in-memory implementation, useful for testing
(deftype SyncEvents [listeners]
  p/EventPoster
  (post-events [this evt]
    ;; Re-dispatch all events that were the result of the listeners
    (loop [all (if (sequential? evt) evt [evt])]
      (when-not (empty? all)
        (recur (post-one @listeners all))))
    this)
  
  p/EventReceiver
  (add-listener [this l]
    ;; It's up to the listener to do event filtering.
    ;; Possible problem: with many listeners and many events, this may become slow.
    (swap! listeners (comp vec conj) l)
    this)

  (remove-listener [this l]
    (swap! listeners (comp vec (partial remove (partial = l))))
    this))

(defn make-sync-events []
  (->SyncEvents (atom [])))

(defn filter-type [t f]
  (fn [evt]
    (when (= t (:type evt))
      (f evt))))

(defn no-dispatch
  "Wraps `f` so that it always returns `nil`, to avoid events being re-dispatched."
  [f]
  (fn [evt]
    (f evt)
    nil))

(defmulti make-events (comp :type :events))

(defmethod make-events :sync [_]
  (make-sync-events))

(defmethod make-events :manifold [_]
  (manifold/make-manifold-events))

(defmethod make-events :zmq [config]
  (zmq/make-zeromq-events (:events config)))

(defmethod rt/setup-runtime :events [conf _]
  (when (:events conf)
    (let [e (make-events conf)]
      (cond-> {:poster (partial post-events e)}
        (satisfies? p/EventReceiver e) (assoc :receiver e)))))

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
