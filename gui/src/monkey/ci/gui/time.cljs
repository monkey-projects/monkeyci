(ns monkey.ci.gui.time
  (:require ["luxon" :refer [DateTime Interval]]
            [clojure.string :as cs]
            [re-frame.core :as rf]))

(defn now []
  (.now DateTime))

(defn local-date [y m d]
  (.local DateTime y m d))

(defn format-iso [^DateTime d]
  (when d
    (.toISO d)))

(defn format-iso-date [^DateTime d]
  (when d
    (.toISODate d)))

(defn format-datetime [^DateTime d]
  (when d
    (.toLocaleString d (.-DATETIME_SHORT DateTime))))

(defn format-date [^DateTime d]
  (when d
    (.toLocaleString d (.-DATE_SHORT DateTime))))

(defn parse-iso
  "Parses ISO datetime string"
  [s]
  (when (not-empty s)
    (.fromISO DateTime s)))

(defn parse-epoch
  "Parses epoch milliseconds to datetime"
  [m]
  (when m
    (.fromMillis DateTime m)))

(defn to-epoch [^DateTime d]
  (when d
    (.toMillis d)))

(defn same?
  "Compares to dates"
  [^DateTime a ^DateTime b]
  (and a (.equals a b)))

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

;; Adds current epoch time to the cofx
(rf/reg-cofx
 :time/now
 (fn [cofx]
   (assoc cofx :time/now (to-epoch (now)))))

(defn date-seq
  "Lazy sequence of dates.  Each next item in the seq is the next day."
  [^DateTime from]
  (lazy-seq (cons from (date-seq (.plus from (clj->js {:days 1}))))))

(defn reverse-date-seq
  "Inverse lazy sequence of dates: each next item is the previous day"
  [^DateTime until]
  (lazy-seq (cons until (reverse-date-seq (.minus until (clj->js {:days 1}))))))

(defn minus-days [^DateTime t days]
  (.minus t (clj->js {:days days})))
