(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.process :as proc])
  (:import java.io.File))

(defmulti new-runner (comp :type :runner))

(defn- run-script [^File dir]
  (if (.exists dir)
    (do
      (log/info "Running build script at" dir)
      (:exit (proc/execute! (.getAbsolutePath dir))))
    (log/info "No build script found at" dir)))

(defn local-runner [ctx]
  (-> (get-in ctx [:script :dir])
      (io/file)
      (run-script)))

(defmethod new-runner :noop
  ;; Provided for testing purposes
  [_]
  (constantly :noop))

(defmethod new-runner :local [_]
  local-runner)

(defmethod new-runner :default [_]
  local-runner)

(defn make-runner
  "Creates a build runner from the given configuration"
  [config]
  (log/debug "Creating runner with config" (:runner config))
  (new-runner config))
