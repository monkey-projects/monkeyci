(ns monkey.ci.gui.utils
  (:require [clojure.string :as cs]
            [monkey.ci.gui.time :as t]
            [re-frame.core :as rf]))

(defn find-by-id [id items]
  (->> items
       (filter (comp (partial = id) :id))
       (first)))

(def error-msg (some-fn :message (comp :error-description :body) :error-text str))

(defn link-evt-handler
  "Creates an event handler that dispatches an event when the user clicks a link"
  [evt]
  (fn [e]
    #?(:cljs (.preventDefault e true))
    (rf/dispatch evt)))

(defn ->sid [m & keys]
  (let [g (apply juxt keys)]
    (cs/join "/" (g m))))

(defn build-elapsed
  "Calculates elapsed time for the build.  This is the difference between the start
   time and the latest pipeline step end time."
  [b]
  (let [s (some-> b
                  :timestamp
                  (t/parse)
                  (t/to-epoch))
        e (->> b
               :pipelines
               (mapcat (fn [p]
                         (map :end-time (:steps p))))
               (apply max))]
    (if (and s e)
      (- e s)
      0)))
