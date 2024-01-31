(ns monkey.ci.events.core)

(defprotocol EventPoster
  (post-events [poster evt] "Posts one or more events"))

(defprotocol EventReceiver
  (add-listener [recv l] "Add the given listener to the receiver")
  (remove-listener [recv l] "Removes the listener from the receiver"))

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
  EventPoster
  (post-events [this evt]
    ;; Re-dispatch all events that were the result of the listeners
    (loop [all [evt]]
      (when-not (empty? all)
        (recur (post-one @listeners all))))
    this)
  
  EventReceiver
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
