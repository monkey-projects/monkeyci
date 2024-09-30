(ns monkey.ci.runners.server
  "Runner that can be configured to run builds directly from the server."
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [commands :as cmd]
             [runners :as r]
             [runtime :as rt]]))

(defmethod r/make-runner :server [conf]
  ;; Runner that is invoked when the build is run by the server itself.  The runtime
  ;; has to be modified so it contains the correct properties.
  (log/info "Using server build runner")
  (fn [build rt]
    (-> (rt/config rt)
        (assoc :build build
               :runner {:type :local})
        (cmd/run-build))))

