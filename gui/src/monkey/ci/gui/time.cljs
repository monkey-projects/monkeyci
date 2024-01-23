(ns monkey.ci.gui.time
  (:require ["luxon" :refer [DateTime Interval]]
            [clojure.string :as cs]))

(defn now []
  (.now DateTime))

(defn local-date [y m d]
  (.local DateTime y m d))

(defn format-iso [^DateTime d]
  (when d
    (.toISO d)))

(defn format-datetime [^DateTime d]
  (when d
    (.toLocaleString d (.-DATETIME_SHORT DateTime))))

(defn parse-iso
  "Parses ISO datetime string"
  [s]
  (.fromISO DateTime s))

(defn parse-epoch
  "Parses epoch milliseconds to datetime"
  [m]
  (.fromMillis DateTime m))

(defn to-epoch [^DateTime d]
  (.toMillis d))

(defn parse [x]
  (cond
    (string? x) (parse-iso x)
    (number? x) (parse-epoch x)))

(defn interval [s e]
  (.fromDateTimes Interval s e))

(defn seconds
  "Returns interval seconds"
  ([i]
   (.length i "seconds"))
  ([s e]
   (seconds (interval s e))))

(defn- pad-left [v]
  (let [s (str v)]
    (cond->> s
      (= 1 (count s)) (str "0"))))

(defn format-seconds
  "Formats the given number of seconds in a human readable string `HH:MM:SS`"
  [ts]
  (let [h (int (/ ts 3600))
        m (int (/ (mod ts 3600) 60))
        s (mod ts 60)]
    (->> [h m s]
         (map pad-left)
         (cs/join ":"))))

(defn format-interval
  "Formats interval in hh:mm:ss"
  ([i]
   (format-seconds (seconds i)))
  ([s e]
   (when (and s e)
     (format-interval (interval s e)))))

(defn reformat [x]
  (some-> (parse x)
          (format-datetime)))
