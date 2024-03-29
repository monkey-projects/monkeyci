(ns monkey.ci.gui.utils
  (:require [clojure.string :as cs]
            [goog.string :as gstring]
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

(defn- evt->value [e]
  #?(:cljs (-> e .-target .-value)
     :clj (:value e)))

(defn- evt->checked [e]
  #?(:cljs (-> e .-target .-checked)
     :clj (:value e)))

(defn form-evt-handler
  [evt & [get-val]]
  (fn [e]
    (rf/dispatch (conj evt ((or get-val evt->value) e)))))

(defn ->sid [m & keys]
  (let [g (apply juxt keys)]
    (cs/join "/" (g m))))

(defn build-elapsed
  "Calculates elapsed time for the build.  This is the difference between the start
   and end times.  If there is no end time yet, returns zero."
  [b]
  (let [s (some-> b
                  :start-time
                  (t/parse)
                  (t/to-epoch))
        e (some-> b
                  :end-time
                  (t/parse)
                  (t/to-epoch))]
    (if (and s e)
      (- e s)
      0)))

(defn running?
  "True if step or pipeline is still running"
  [x]
  (nil? (:end-time x)))

(defn ->dom-id [id]
  (str "#" (name id)))

(defn db-sub
  "Registers a sub that returns a single value from db"
  [id f]
  (rf/reg-sub id (fn [db _] (f db))))

(defn unescape-entity [e]
  (gstring/unescapeEntities e))
