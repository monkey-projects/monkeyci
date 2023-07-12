;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])

#_(core/pipeline
 {:name "monkey-ci"
  :steps [{:name "builder-test"
           :work-dir "builder"
           :container/image "clojure:temurin-20-tools-deps-alpine"
           :clojure/cli ["-X:test"]}]})

(println "Hi there!")
 
(comment
  ;; Maybe above could be abbreviated into:
  (core/simple-pipeline
   "monkey-ci"
   (core/named-step "builder-test"
                    :work-dir "builder"
                    :container/image "clojure:temurin-20-tools-deps-alpine"
                    :clojure/cli ["-X:test"])))

core/success
