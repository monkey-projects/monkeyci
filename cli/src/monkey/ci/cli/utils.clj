(ns monkey.ci.cli.utils
  (:require [babashka.fs :as fs]))

(def script-dir ".monkeyci")

(defn find-script-dir
  "Finds the script directory in the given dir.  If no default script dir is present,
   returns `dir`."
  [dir]
  (let [s (fs/path dir script-dir)]
    (str (if (fs/exists? s) s dir))))
