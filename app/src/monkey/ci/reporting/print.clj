(ns monkey.ci.reporting.print
  "Reporter that prints to the console using coloring."
  (:require [clansi :as cl]
            [monkey.ci.reporting :as r]))

(def good (cl/style "\u221a" :bright :green))
(def bad  (cl/style "X" :bright :red))

(defmulti printer :type)

(defmethod printer :server/started [msg]
  (println "Server started at port" (get-in msg [:http :port]) good)
  (println "Press" (cl/style "Ctrl+C" :cyan) "to stop."))

(defn print-reporter
  "Nicely prints to stdout, using coloring"
  [obj]
  (printer obj))

(defmethod r/make-reporter :print [_]
  print-reporter)
