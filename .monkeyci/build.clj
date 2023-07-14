;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.shell :as shell])

(defn success-step [ctx]
  (println "I'm sure I'll succeed!")
  core/success)

(defn failing-step [ctx]
  (println "Hi there! I should fail.")
  core/failure)

(def test-script
  "Runs unit tests on the script library"
  {:work-dir "lib"
   :action (shell/bash "clojure -X:test")})
 
(comment
  ;; Maybe above could be abbreviated into:
  (core/simple-pipeline
   "monkey-ci"
   (core/named-step "builder-test"
                    :work-dir "lib"
                    :container/image "clojure:temurin-20-tools-deps-alpine"
                    :clojure/cli ["-X:test"])))

;; Return the pipelines
(core/pipeline
 {:name "build"
  :steps [test-script]})
