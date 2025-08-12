;; Basic Clojure build script
(ns build
  (:require [monkey.ci.api :as m]))

;; Jobs that just print messages to stdout
[(m/action-job
  "first"
  (m/bash "echo \"Hi, I'm a simple build script!\""))
 (m/action-job
  "second"
  (m/bash "echo" "And I'm another part of that script"))]

