(ns monkey.ci.step-runner)

(defprotocol StepRunner
  (run-step [this ctx]))

(extend-type clojure.lang.Fn
  StepRunner
  (run-step [f ctx]
    (f ctx)))
