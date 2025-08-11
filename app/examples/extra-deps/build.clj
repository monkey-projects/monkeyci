(ns build
  (:require [monkey.ci.api :as m]
            ;; This dependency is included through the deps.edn file
            [camel-snake-kebab.core :as csk]))

(m/action-job
 "extra-deps"
 (fn [_]
   (assoc m/success :output (csk/->snake_case "SomeSimpleScript"))))
               
