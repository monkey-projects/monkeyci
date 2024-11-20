(ns monkey.ci.runners.server
  "Runner that can be configured to run builds directly from the server."
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [commands :as cmd]
             [runners :as r]
             [runtime :as rt]
             [utils :as u]]))

(defmethod r/make-runner :server [conf]
  ;; Runner that is invoked when the build is run by the server itself.  The runtime
  ;; has to be modified so it contains the correct properties.
  (log/info "Using server build runner")
  (fn [build rt]
    (let [conf (rt/config rt)]
      (-> conf
          (assoc :build (assoc-in build [:git :dir] (u/combine (:work-dir conf) (:build-id build)))
                 :runner (assoc (:runner conf) :type :local))
          (cmd/run-build)))))

