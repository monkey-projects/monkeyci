(ns monkey.ci.events.core
  (:require [monkey.ci.time :as t]))

(defn make-event
  "Creates a new event with required properties.  Additional properties are given as
   map keyvals, or as a single map."
  [type & props]
  (-> (if (= 1 (count props))
        (first props)
        (apply hash-map props))
      (assoc :type type
             :time (t/now))))

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
