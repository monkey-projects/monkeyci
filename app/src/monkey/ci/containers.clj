(ns monkey.ci.containers
  "Generic functionality for running containers")

(defmulti run-container :container-runner)
