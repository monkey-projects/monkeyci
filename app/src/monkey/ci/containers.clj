(ns monkey.ci.containers
  "Generic functionality for running containers")

(defmulti run-container (comp :type :containers))
