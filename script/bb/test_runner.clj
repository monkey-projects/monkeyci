(ns test-runner
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [clojure.test.junit :as j]
            [babashka.classpath :as cp]))

(defn run-tests []
  (cp/add-classpath "src:test")
  (require 'monkey.ci.script.api-client-test
           'monkey.ci.script.config-test
           'monkey.ci.script.core-test
           'monkey.ci.script.events-test
           'monkey.ci.script.jobs-test
           'monkey.ci.script.load-test)
  (let [{:keys [fail error]} (t/run-all-tests (re-pattern "^monkey.ci.script.*-test$"))]
    (when (pos? (+ fail error))
      (System/exit 1))))

(defn run-junit-tests [path]
  (with-open [pw (io/writer (io/file path))]
    (binding [t/*test-out* pw]
      (j/with-junit-output
        (run-tests)))))
