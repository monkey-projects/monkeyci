(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci
             [process :as proc]
             [utils :as u]])
  (:import java.io.File))

(defmulti new-runner (comp :type :runner))

(defn- get-absolute-dirs [{:keys [dir workdir]}]
  (let [wd (io/file (or workdir (u/cwd)))]
    {:script-dir (.getCanonicalPath (io/file (u/abs-path wd dir)))
     :work-dir (.getCanonicalPath wd)}))

(defn child-runner
  "Creates a new runner that executes the script locally in a child process"
  [ctx]
  (let [{:keys [script-dir work-dir] :as dirs} (get-absolute-dirs (:script ctx))]
    (if (.exists (io/file script-dir))
      (do
        (log/info "Running build script at" script-dir)
        (:exit (proc/execute! dirs)))
      (log/warn "No build script found at" script-dir))))

(defmethod new-runner :noop
  ;; Provided for testing purposes
  [_]
  (constantly :noop))

(defmethod new-runner :local [_]
  child-runner)

(defmethod new-runner :child [_]
  child-runner)

(defmethod new-runner :default [_]
  child-runner)

(defn make-runner
  "Creates a build runner from the given configuration"
  [config]
  (log/debug "Creating runner with config" (:runner config))
  (new-runner config))
