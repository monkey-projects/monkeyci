(ns monkey.ci.events.manifold
  "Manifold-based implementation of event poster and receiver"
  (:require [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.protocols :as p]))

(defn- post [stream e]
  ;; Either post one event, or multiple
  (if (sequential? e)
    (ms/put-all! stream (filter some? e))
    (ms/put! stream e)))

(deftype ManifoldEvents [filter-fn stream]
  p/EventPoster
  (post-events [this e]
    (post stream e)
    this)

  p/EventReceiver
  (add-listener [this ef l]
    (let [s (ms/stream 10)]
      (ms/connect stream s
                  {:description {::listener l
                                 ::filter ef}})
      (ms/consume-async (fn [evt]
                          (when (filter-fn evt ef)
                            (l evt))       ; Ignore the return value
                          (md/success-deferred true))
                        s)
      this))
  
  (remove-listener [this ef l]
    ;; Find the downstream with given listener in the description
    (->> (ms/downstream stream)
         (filter (fn [[{:keys [::listener ::filter]} sink]]
                   (and (= l listener) (= ef filter))))
         (map (comp ms/close! second))
         (doall))
    this))

(defn make-manifold-events [filter-fn]
  (->ManifoldEvents filter-fn
                    (ms/stream* {:permanent? true
                                 :description #(assoc % ::desc "Event bus")})))
