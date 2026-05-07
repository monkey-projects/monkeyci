(ns monkey.ci.cli.utils
  (:require [babashka.fs :as fs]
            [monkey.ci.cli.build :as b]))

(defn find-script-dir
  "Finds the script directory in the given dir.  If no default script dir is present,
   returns `dir`."
  [dir]
  (let [s (fs/path dir b/default-script-dir)]
    (str (if (fs/exists? s) s dir))))
