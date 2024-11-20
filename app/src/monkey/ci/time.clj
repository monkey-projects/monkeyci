(ns monkey.ci.time
  "Time related utility functions"
  (:require [java-time.api :as jt])
  (:import java.time.OffsetDateTime))

(defn now []
  (System/currentTimeMillis))

(defn hours->millis [h]
  (* h 3600 1000))

(defn day-start
  "Given an offset date time, returns the date at midnight"
  [^OffsetDateTime date]
  (-> date
      (jt/local-date)
      (jt/offset-date-time (jt/local-time 0) (jt/zone-offset date))))

(defn date-seq
  "Lazy seq of dates, starting at given date."
  [^OffsetDateTime start]
  (let [m (day-start start)]
    (lazy-seq (cons m (date-seq (jt/plus m (jt/days 1)))))))
