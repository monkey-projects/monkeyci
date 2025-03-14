(ns monkey.ci.time
  "Time related utility functions"
  (:require [clojure.tools.logging :as log]
            [java-time.api :as jt])
  (:import java.time.OffsetDateTime))

(defn now []
  (System/currentTimeMillis))

(defn hours->millis [h]
  (* h 3600 1000))

(defn day-start
  "Returns the date at midnight for given time object"
  [date]
  (jt/truncate-to date :days))

(defn date-seq
  "Lazy seq of dates, starting at given date."
  [^OffsetDateTime start]
  (let [m (day-start start)]
    (lazy-seq (cons m (date-seq (jt/plus m (jt/days 1)))))))

(def utc-zone (jt/zone-id "UTC"))

(defn epoch->date
  "Converts epoch millis to local date using utc zone"
  [ms]
  (-> (jt/instant ms)
      (jt/local-date utc-zone)))

(defn same-date?
  "True if the two epoch millis denote the same UTC date"
  [a b]
  (and a b
       (= (epoch->date a)
          (epoch->date b))))

(defn same-dom?
  "True if the two epoch millis are about the same day-of-month (in UTC)"
  [a b]
  (->> [a b]
       (map (comp #(jt/as % :day-of-month)
                  epoch->date))
       (apply =)))
