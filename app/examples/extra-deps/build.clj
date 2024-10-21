(require '[monkey.ci.build.core :as c])
;; This dependency is included through the deps.edn file
(require '[camel-snake-kebab.core :as csk])

(c/action-job
 "extra-deps"
 (fn [_]
   (assoc c/success :output (csk/->snake_case "SomeSimpleScript"))))
               
