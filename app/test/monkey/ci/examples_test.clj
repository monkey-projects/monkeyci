(ns monkey.ci.examples-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging :as log]
            [monkey.ci
             [cli :as cli]
             [core :as core]]))

(defn run-example [path]
  (log/info "Running example at" path)
  (let [inv (-> cli/run-build-cmd
                :runs
                (core/system-invoker {}))]
    (inv {:dev-mode true
          :workdir "examples"
          :dir path})))

(defn success? [r]
  (= 0 r #_(deref r 30000 :timeout)))

(deftest ^:integration examples
  (letfn [(run-example-test [n]
            (testing (format "runs %s example" n)
              (is (success? (run-example n)))))]
    (->> ["basic-clj"
          "basic-script"
          "build-params"]
         (map run-example-test)
         (doall))))
