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
    {:script-dir (some->> dir
                          (u/abs-path wd)
                          (io/file)
                          (.getCanonicalPath))
     :work-dir (some-> wd (.getCanonicalPath))}))

(defn child-runner
  "Creates a new runner that executes the script locally in a child process"
  [ctx]
  (let [{:keys [script-dir work-dir] :as dirs} (get-absolute-dirs (:args ctx))]
    (if (some-> (io/file script-dir) (.exists))
      (do
        (log/info "Running build script at" script-dir)
        (let [{:keys [exit] :as out} (-> ctx
                                         (merge dirs)
                                         (assoc :pipeline (get-in ctx [:args :pipeline]))
                                         (proc/execute!))]
          (cond-> out
            true (assoc :result :error)
            (zero? exit) (assoc :result :success))))
      (do
        (log/warn "No build script found at" script-dir)
        {:exit 1
         :result :warning}))))

(defmethod new-runner :noop
  ;; Provided for testing purposes
  [_]
  (constantly {:runner :noop
               :exit 0}))

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
