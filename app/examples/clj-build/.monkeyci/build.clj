(ns build
  (:require [monkey.ci.api :as m]))

(def run-tests
  (-> (m/container-job "unit-tests")
      (m/image "docker.io/clojure:tools-deps-bookworm-slim")
      (m/script ["clojure -Sdeps '{:mvn/local-repo \"m2\"}' -X:test"])
      (m/caches (m/cache "maven-cache" "m2"))))

[run-tests]
