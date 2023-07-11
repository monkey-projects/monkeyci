(ns monkey.ci.core
  "Core namespace for the Monkey CI app"
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [monkey.ci.process :as proc]))

(defn -main
  "Main entry point for the application."
  [& [dir]]
  (let [dir (or dir ".monkeyci")]
    (try
      (when dir
        (log/info "Running build script at" dir)
        (:exit (proc/execute! dir)))
      (finally
        ;; Shutdown the agents otherwise the app will block for a while here
        (shutdown-agents)))))
