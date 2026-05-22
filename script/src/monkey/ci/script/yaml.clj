(ns monkey.ci.script.yaml
  "Functions for loading yaml script files"
  (:require [camel-snake-kebab.core :as csk]
            [clj-yaml.core :as yaml]
            [flatland.ordered.map :as om]
            [monkey.ci.script
             [jobs :as j]
             [utils :as u]]))

;; Required for yaml
;; Retrieve the ordered map class using this workaround for Babashka
(extend-type (class (om/ordered-map))
  j/JobResolvable
  (resolve-jobs [m rt]
    (when (j/job? m) [m])))

(defn load-yaml [path]
  (-> (slurp path)
      (yaml/parse-string :key-fn (comp csk/->kebab-case-keyword :key))
      (u/normalize)))
