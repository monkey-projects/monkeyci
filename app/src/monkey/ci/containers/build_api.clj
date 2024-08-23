(ns monkey.ci.containers.build-api
  "Container runner that invokes an endpoint on the build api.  This is meant to
   be used by child processes that do not have full infra permissions."
  (:require [monkey.ci.containers :as mcc]))

(defmethod mcc/run-container :build-api [conf]
  ;; TODO Start container
  ;; TODO Poll for status updates, or use stream?
  )
