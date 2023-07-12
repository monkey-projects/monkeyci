(ns monkey.ci.core
  "Core namespace for the Monkey CI app"
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.process :as proc]))

(defn -main
  "Main entry point for the application."
  [& [dir]]
  (let [dir (io/file (or dir ".monkeyci"))]
    (try
      (if (.exists dir)
        (do
          (log/info "Running build script at" dir)
          (:exit (proc/execute! (.getAbsolutePath dir))))
        (log/info "No build script found at" dir))
      (finally
        ;; Shutdown the agents otherwise the app will block for a while here
        (shutdown-agents)))))
