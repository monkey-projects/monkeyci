(ns monkey.ci.core
  "Core namespace for the Monkey CI app.  This contains the entrypoint which
   processes the configuration.  This configuration determines whether the
   application runs as a server, execute a single script, which type of runner
   is enabled, etc..."
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.process :as proc]))

(defn- run-script [dir]
  (if (.exists dir)
    (do
      (log/info "Running build script at" dir)
      (:exit (proc/execute! (.getAbsolutePath dir))))
    (log/info "No build script found at" dir)))

(defn -main
  "Main entry point for the application."
  [& [dir]]
  (let [dir (io/file (or dir ".monkeyci"))]
    (try
      (run-script dir)
      (finally
        ;; Shutdown the agents otherwise the app will block for a while here
        (shutdown-agents)))))
