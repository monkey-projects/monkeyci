(ns monkey.ci.cli.print
  (:require [clansi :as ansi]))

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
