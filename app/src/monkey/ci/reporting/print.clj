(ns monkey.ci.reporting.print
  "Reporter that prints to the console using coloring."
  (:require [clansi :as cl]
            [monkey.ci.reporting :as r]
            [monkey.ci.build.core :as bc]))

(def good (cl/style "\u221a" :bright :green))
(def bad  (cl/style "X" :bright :red))

(defn- url [url]
  (cl/style url :underline))

(defn- accent [s]
  (cl/style s :bright :yellow))

(defmulti printer :type)

(defn- print-stop []
  (println "Press" (cl/style "Ctrl+C" :cyan) "to stop."))

(defmethod printer :server/started [msg]
  (println "Server started at" (url (format "http://localhost:%d" (get-in msg [:http :port]))) good)
  (print-stop))

(defmethod printer :watch/started [m]
  (println "Watching for build events at" (url (:url m)))
  (print-stop))

(defmethod printer :build/event [{:keys [event]}]
  (case (:type event)
    :script/start
    (println (cl/style "Script started" :green))
    :script/end
    (println (cl/style "Script completed" :green))
    :pipeline/start
    (println "Pipeline started:" (accent (:pipeline event)))
    :pipeline/end
    (if (bc/success? event)
      (println "Pipeline succeeded" good)
      (println "Pipeline failed" bad))
    :step/start
    (println "Step started:" (accent (or (:name event)
                                         (str "index " (:index event)))))
    :step/end
    (if (bc/success? event)
      (println "Step succeeded" good)
      (do
        (println "Step failed" bad)
        (println "Message:" (accent (:message event)))))
    ;; Other cases, just ignore
    nil))

(defmethod printer :default [msg]
  (println (cl/style "Warning:" :bright :cyan) "unknown message type" (accent (str (:type msg)))))

(defn print-reporter
  "Nicely prints to stdout, using coloring"
  [obj]
  (printer obj))

(defmethod r/make-reporter :print [_]
  print-reporter)
