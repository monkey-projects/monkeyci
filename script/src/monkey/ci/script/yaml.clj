(ns monkey.ci.script.yaml
  "Functions for loading yaml script files"
  (:require [camel-snake-kebab.core :as csk]
            [clj-yaml.core :as yaml]
            [monkey.ci.script
             [jobs :as j]
             [utils :as u]]))

;; Required for yaml
(extend-type flatland.ordered.map.OrderedMap
  j/JobResolvable
  (resolve-jobs [m rt]
    (when (j/job? m) [m])))

(defn load-yaml [path]
  (-> (slurp path)
      (yaml/parse-string :key-fn (comp csk/->kebab-case-keyword :key))
      (u/normalize)))
