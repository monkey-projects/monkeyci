(ns monkey.ci.internal
  "Entry point for interal use: when the app is run as a server or build agent.
   This is mostly the same as the user-facing entrypoint, but uses a different
   cli configuration."
  (:gen-class)
  (:require [monkey.ci
             [cli :as cli]
             [core :as core]]))

(defn -main [& args]
  (core/run-cli cli/internal-config args))
