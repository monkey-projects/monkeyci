(ns test-runner
  (:require [clojure.test :as t]
            [babashka.classpath :as cp]))

(defn run-tests []
  (cp/add-classpath "src:test")
  (require 'monkey.ci.script.api-client-test
           'monkey.ci.script.core-test
           'monkey.ci.script.config-test)
  (let [{:keys [fail error]} (t/run-all-tests (re-pattern "^monkey.ci.script.*-test$"))]
    (when (pos? (+ fail error))
      (System/exit 1))))
