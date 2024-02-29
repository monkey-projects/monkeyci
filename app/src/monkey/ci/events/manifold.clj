(ns monkey.ci.events.manifold
  "Manifold-based implementation of event poster and receiver"
  (:require [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.events.core :as c]))

(defn- post [stream e]
  ;; Either post one event, or multiple
  (if (sequential? e)
    (ms/put-all! stream (filter some? e))
    (ms/put! stream e)))

(deftype ManifoldEvents [stream]
  c/EventPoster
  (post-events [this e]
    (post stream e)
    this)

  c/EventReceiver
  (add-listener [this l]
    (let [s (ms/stream 10)]
      (ms/connect stream s
                  {:description {::listener l}})
      (ms/consume-async
       (fn [evt]
         ;; TODO This may deadlock
         (if-let [r (l evt)]
           (post stream r)
           (md/success-deferred true)))
       s)
      this))
  
  (remove-listener [this l]
    ;; Find the downstream with given listener in the description
    (->> (ms/downstream stream)
         (filter (fn [[{:keys [::listener]} sink]]
                   (= l listener)))
         (map (comp ms/close! second))
         (doall))
    this))

(defn make-manifold-events []
  (->ManifoldEvents (ms/stream* {:permanent? true
                                 :description #(assoc % ::desc "Event bus")})))

(defmethod c/make-events :manifold [_]
  (make-manifold-events))
