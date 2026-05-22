(ns monkey.ci.script.edn
  "Functions for loading edn build scripts"
  (:require [clojure.edn :as edn]
            [monkey.ci.script.utils :as u]))

(defn load-edn [path]
  (-> path
      (slurp)
      (edn/read-string)
      (u/normalize)))
