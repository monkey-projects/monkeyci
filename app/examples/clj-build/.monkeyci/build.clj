(ns build
  (:require [monkey.ci.build.core :as c]))

(def run-tests
  {:name "unit-tests"
   :container/image "docker.io/clojure:tools-deps-bookworm-slim"
   :script ["clojure -Sdeps '{:mvn/local-repo \"m2\"}' -X:test"]
   :caches [{:id "maven-cache"
             :path "m2"}]})

(c/defpipeline test-all
  [run-tests])
