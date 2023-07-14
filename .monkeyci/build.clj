;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[clojure.tools.logging :as log])

#_(core/pipeline
 {:name "monkey-ci"
  :steps [{:name "builder-test"
           :work-dir "builder"
           :container/image "clojure:temurin-20-tools-deps-alpine"
           :clojure/cli ["-X:test"]}]})

(defn success-step [ctx]
  (println "I'm sure I'll succeed!")
  core/success)

(defn failing-step [ctx]
  (println "Hi there! I should fail.")
  core/failure)
 
(comment
  ;; Maybe above could be abbreviated into:
  (core/simple-pipeline
   "monkey-ci"
   (core/named-step "builder-test"
                    :work-dir "builder"
                    :container/image "clojure:temurin-20-tools-deps-alpine"
                    :clojure/cli ["-X:test"])))

(core/pipeline
 {:name "build"
  :steps [success-step]})
