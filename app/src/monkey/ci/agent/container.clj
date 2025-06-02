(ns monkey.ci.agent.container
  "Entrypoint for the container agent.  This is similar to build agents, but
   runs container jobs using Podman instead."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [monkey.ci.agent.runtime :as ar]
            [monkey.ci.config :as c]
            [monkey.ci.runtime.common :as rc]))

(defn run-agent [conf]
  (log/info "Starting build agent")
  (rc/with-system
    (ar/make-container-system conf)
    (fn [sys]
      ;; Wait for the poll loop to end
      @(get-in sys [:poll-loop :future]))))

(defn -main [& args]
  (run-agent (c/load-config-file (first args))))

