(ns monkey.ci.agent.container
  "Entrypoint for the container agent.  This is similar to build agents, but
   runs container jobs using Podman instead."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [monkey.ci.agent.runtime :as ar]
            [monkey.ci.config :as c]
            [monkey.ci.runtime.common :as rc]))

(defn run-agent [conf f]
  (log/info "Starting build agent")
  (rc/with-system
    (ar/make-container-system conf)
    f))

(defn -main [& args]
  (run-agent (c/load-config-file (first args)) #(deref (get-in % [:poll-loop :future]))))

