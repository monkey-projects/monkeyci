(ns monkey.ci.script.json
  "Functions for loading json script files"
  (:require [clojure.java.io :as io]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [monkey.ci.script.utils :as u]))

(defn load-json [path]
  (with-open [r (io/reader path)]
    (-> (json/parse-stream r csk/->kebab-case-keyword)
        (u/normalize))))



