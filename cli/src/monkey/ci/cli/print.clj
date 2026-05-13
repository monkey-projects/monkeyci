(ns monkey.ci.cli.print
  (:require [clansi :as ansi])
  (:import java.time.LocalDateTime))

(set! *warn-on-reflection* true)

(defn print-version [version]
  (println (str (ansi/style "Monkey" :green :bright)
                (ansi/style "CI" :yellow :bright))
           "version" version))

(defn print-summary [{:keys [error warning info files duration]}]
  (println (str (ansi/style "Linting summary" :cyan :bright) "\n"
                "  Files   : " files "\n"
                "  Errors  : " (ansi/style (str error)   (if (pos? error)   :red    :default)) "\n"
                "  Warnings: " (ansi/style (str warning) (if (pos? warning) :yellow :default)) "\n"
                "  Info    : " info "\n"
                "  Duration: " duration "ms"))
  (when (and (not (pos? error)) (not (pos? warning)))
    (println (ansi/style "Everything is ok!" :green :bright))))

(defn print-finding [{:keys [filename row message]}]
  (println (str (ansi/style filename :yellow)
                " - line " row ": "
                (ansi/style message :red))))

(defn print-findings [findings]
  (println (ansi/style (str "Got " (count findings) " findings:") :red :bright))
  (doseq [f findings]
    (print-finding f)))

(defn print-msg [& msgs]
  (apply println msgs))

(defn now []
  (LocalDateTime/now))

(def time-format
  (let [l (System/getenv "LANGUAGE")]
    (cond-> (java.time.format.DateTimeFormatter/ofLocalizedTime java.time.format.FormatStyle/MEDIUM)
      l (.withLocale (java.util.Locale/of l)))))

(defn format-time
  ([^LocalDateTime t]
   (.format t time-format))
  ([]
   (format-time (now))))

(defn print-timed-msg [& parts]
  (apply println (ansi/style (str "[" (format-time) "]") :yellow) parts))

(defn print-job-msg [job & parts]
  (apply print-timed-msg (ansi/style (str "[" job "]") :bright :cyan) parts))

(defn success [p]
  (ansi/style p :bright :green))

(defn failure [p]
  (ansi/style p :bright :red))
