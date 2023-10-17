(ns monkey.ci.test.examples-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging :as log]
            [monkey.ci.core :as core]
            [monkey.ci.test.helpers :as h]))

(defn run-example [path]
  (log/info "Running example at" path)
  (let [inv (-> core/build-cmd
                :runs
                (core/system-invoker {}))]
    (inv {:workdir "examples"
          :dir path
          :dev-mode true})))

(defn success? [r]
  (= 0 (h/try-take r 30000 :timeout)))

(deftest ^:integration examples
  (letfn [(run-example-test [n]
            (testing (format "runs %s example" n)
              (is (success? (run-example n)))))]
    (->> ["basic-clj"
          "basic-script"
          "build-params"]
         (map run-example-test)
         (doall))))
