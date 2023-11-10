(ns monkey.ci.reporting.print
  "Reporter that prints to the console using coloring."
  (:require [clansi :as cl]
            [clojure.string :as cs]
            [monkey.ci.reporting :as r]
            [monkey.ci.build.core :as bc]))

(def good (cl/style "\u221a" :bright :green))
(def bad  (cl/style "X" :bright :red))
(def prev-line "\033[F") ; ANSI code to jump back to the start of previous line

(defn- url [url]
  (cl/style url :underline))

(defn- accent [s]
  (cl/style s :bright :yellow))

(defn- overwrite
  "Overwrites the previous line with the string"
  [s & args]
  (apply println prev-line s args))

(defmulti printer :type)

(defn- print-stop []
  (println "Press" (cl/style "Ctrl+C" :cyan) "to stop."))

(defmethod printer :server/started [msg]
  (println "Server started at" (url (format "http://localhost:%d" (get-in msg [:http :port]))) good)
  (print-stop))

(defmethod printer :watch/started [m]
  (println "Watching for build events at" (url (:url m)))
  (print-stop))

(defn- step-name [event]
  (or (:name event)
      (str "index " (:index event))))

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
      (println "Pipeline" (accent (:pipeline event)) "succeeded" good)
      (println "Pipeline" (accent (:pipeline event)) "failed" bad))
    :step/start
    (println "Step started:" (accent (step-name event)))
    :step/end
    (if (bc/success? event)
      (println "Step succeeded:" (accent (step-name event)) good)
      (do
        (println "Step failed:" (accent (step-name event)) bad)
        (println "Message:" (accent (:message event)))))
    ;; Other cases, just ignore
    nil))

(defmethod printer :build/list [{:keys [builds]}]
  (println "Builds:" (accent (count builds)))
  (when (not-empty builds)
    (println "Id\tTimestamp\tResult")
    (let [r (juxt :id :timestamp :result)]
      (doseq [b builds]
        (let [[id ts res] (r b)]
          (println (->> [id ts (name res) (if (bc/success? b) good bad)]
                        (cs/join "\t"))))))))

(defmethod printer :default [msg]
  (println (cl/style "Warning:" :bright :cyan) "unknown message type" (accent (str (:type msg)))))

(defn print-reporter
  "Nicely prints to stdout, using coloring"
  [obj]
  (printer obj))

(defmethod r/make-reporter :print [_]
  print-reporter)
