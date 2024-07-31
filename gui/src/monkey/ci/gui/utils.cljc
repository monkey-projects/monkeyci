(ns monkey.ci.gui.utils
  (:require [clojure.string :as cs]
            [goog.string :as gstring]
            [medley.core :as mc]
            [monkey.ci.gui.time :as t]
            [re-frame.core :as rf]))

(defn find-by-id [id items]
  (->> items
       (filter (comp (partial = id) :id))
       (first)))

(def error-msg (some-fn ex-message
                        :message
                        (comp :error-description :body)
                        :error-text
                        :response
                        (comp :status-text :parse-error)
                        :status-text
                        str))

(defn link-evt-handler
  "Creates an event handler that dispatches an event when the user clicks a link"
  [evt]
  (fn [e]
    #?(:cljs (.preventDefault e true))
    (rf/dispatch evt)))

(defn evt->value [e]
  #?(:cljs (-> e .-target .-value)
     :clj (:value e)))

(defn evt->checked [e]
  #?(:cljs (-> e .-target .-checked)
     :clj (:value e)))

(defn form-evt-handler
  [evt & [get-val]]
  (fn [e]
    ;; Dispatch synchronously to avoid losing events on fast input
    (rf/dispatch-sync (conj evt ((or get-val evt->value) e)))))

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
  (rf/reg-sub id (fn [db [_ & args]] (apply f db args))))

(defn unescape-entity [e]
  (gstring/unescapeEntities e))

(defn login-on-401 [ctx {:keys [status]}]
  (cond-> ctx
    (= 401 status) (assoc :dispatch [:route/goto :page/login])))

(defn req-error-handler-db
  "Creates an fx handler fn that redirects to the login page on a 401 error
   and passes the error also to `f`, which can modify the db."
  [f]
  (fn [{:keys [db]} evt]
    (login-on-401
     {:db (f db evt)}
     (last evt))))

(defn parse-int [x]
  (if (string? x)
    #?(:cljs (js/parseInt x)
       :clj (Integer/parseInt x))
    x))

(defn pluralize [s n & [plural]]
  (cond
    (= 1 n) s
    plural plural
    :else (str s "s")))

(defn update-nth
  "Updates an item in a sequential collection"
  [coll idx f & args]
  (mc/replace-nth idx (apply f (nth coll idx) args) coll))
