(ns monkey.ci.runners.controller
  "Functions for running the application as a controller."
  (:require [monkey.ci.runners :as r]))

(defn run-controller [rt]
  (-> (:build rt)
      (r/download-src rt)
      (r/store-src rt)))
