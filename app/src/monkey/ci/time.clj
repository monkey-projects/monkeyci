(ns monkey.ci.time
  "Time related utility functions")

(defn now []
  (System/currentTimeMillis))

(defn hours->millis [h]
  (* h 3600 1000))
