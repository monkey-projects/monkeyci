(ns monkey.ci.test.json
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]))

(defn parse-json [s]
  (json/parse-string s csk/->kebab-case-keyword))

(defn to-json
  "Converts object to json and converts keys to camelCase"
  [obj]
  (json/generate-string obj (comp csk/->camelCase name)))

(defn to-raw-json
  "Converts object to json without converting keys"
  [obj]
  (json/generate-string obj))

