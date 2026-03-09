(ns monkey.ci.internal
  "Entry point for interal use: when the app is run as a server or build agent.
   This is mostly the same as the user-facing entrypoint, but uses a different
   cli configuration."
  (:gen-class)
  (:require [monkey.ci
             [cli :as cli]
             [core :as core]]))

(defn -main [& args]
  ;; Redirect JUL logging to slf4j, for some 3rd party libs.
  ;; Note that this may have performance impact, see https://www.slf4j.org/legacy.html#jul-to-slf4j
  (org.slf4j.bridge.SLF4JBridgeHandler/install)
  (core/run-cli cli/internal-config args))
