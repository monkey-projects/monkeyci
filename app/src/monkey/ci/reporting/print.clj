(ns monkey.ci.reporting.print
  "Reporter that prints to the console using coloring."
  (:require [clansi :as cl]
            [monkey.ci.reporting :as r]))

(def good (cl/style "\u221a" :bright :green))
(def bad  (cl/style "X" :bright :red))

(defn- url [url]
  (cl/style url :underline))

(defmulti printer :type)

(defn- print-stop []
  (println "Press" (cl/style "Ctrl+C" :cyan) "to stop."))

(defmethod printer :server/started [msg]
  (println "Server started at" (url (format "http://localhost:%d" (get-in msg [:http :port]))) good)
  (print-stop))

(defmethod printer :watch/started [m]
  (println "Watching for build events at" (url (:url m)))
  (print-stop))

(defmethod printer :default [msg]
  (println (cl/style "Warning:" :bright :cyan) "unknown message type" (cl/style (str (:type msg)) :bright :yellow)))

(defn print-reporter
  "Nicely prints to stdout, using coloring"
  [obj]
  (printer obj))

(defmethod r/make-reporter :print [_]
  print-reporter)
