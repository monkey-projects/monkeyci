(ns monkey.ci.gui.utils
  (:require [clojure.string :as cs]
            [re-frame.core :as rf]))

(defn find-by-id [id items]
  (->> items
       (filter (comp (partial = id) :id))
       (first)))

(def error-msg (some-fn :message :error-text str))

(defn link-evt-handler
  "Creates an event handler that dispatches an event when the user clicks a link"
  [evt]
  (fn [e]
    #?(:cljs (.preventDefault e true))
    (rf/dispatch evt)))

(defn ->sid [m & keys]
  (let [g (apply juxt keys)]
    (cs/join "/" (g m))))
