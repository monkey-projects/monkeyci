(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci
             [events :as e]
             [process :as p]
             [utils :as u]]))

(def default-script-dir ".monkeyci")

(defn- calc-script-dir
  "Given an (absolute) working directory and scripting directory, determines
   the absolute script dir."
  [wd sd]
  (->> (or sd default-script-dir)
       (u/abs-path wd)
       (io/file)
       (.getCanonicalPath)))

(defn- get-absolute-dirs [{:keys [dir workdir]}]
  (let [wd (io/file (or workdir (u/cwd)))]
    {:script-dir (calc-script-dir wd dir)
     :work-dir (some-> wd (.getCanonicalPath))}))

(defn- script-not-found [{{:keys [script-dir]} :build}]
  (log/warn "No build script found at" script-dir)
  ;; Nonzero exit code
  1)

(defn build-completed [{{:keys [result exit] :as evt} :event :keys [build] :as ctx}]
  ;; Do some logging depending on the result
  (condp = (or result :unknown)
    :success (log/info "Success!")
    :warning (log/warn "Exited with warnings:" (:message evt))
    :error   (log/error "Failure.")
    :unknown (log/warn "Unknown result."))
  (let [wd (get-in build [:git :dir])]
    (when (and wd (not= wd (u/cwd)))
      (log/debug "Cleaning up checkout dir" wd)
      (u/delete-dir wd)))
  exit)

(defn build-local
  "Locates the build script locally and dispatches another event with the
   script details in them.  If no build script is found, dispatches a build
   complete event."
  [{:keys [args event-bus] :as ctx}]
  (let [{:keys [script-dir work-dir] :as build} (-> (get-absolute-dirs args)
                                                    (merge (select-keys args [:pipeline]))
                                                    (merge (:build ctx)))
        ctx (update ctx :build merge build)]
    (if (some-> (io/file script-dir) (.exists))
      (do
        ;; Start child process and wait for it to complete
        (p/execute! ctx)
        (e/wait-for event-bus :build/completed (map (comp build-completed
                                                          (partial e/with-ctx ctx)))))
      (script-not-found ctx))))

(defn- download-git
  "Downloads from git into a temp dir, and designates that as the working dir."
  [ctx]
  (let [git (get-in ctx [:git :fn])
        conf (-> (get-in ctx [:build :git])
                 (update :dir #(or % (get-in ctx [:args :workdir]))))
        sd (get-in ctx [:args :dir])
        add-script-dir (fn [{{:keys [work-dir]} :build :as ctx}]
                         (assoc-in ctx [:build :script-dir] (calc-script-dir work-dir sd)))]
    (log/debug "Checking out git repo with config" conf)
    (-> ctx
        (assoc-in [:build :work-dir] (git conf))
        (add-script-dir))))

(defn download-src
  "Downloads the code from the remote source, if there is one.  If the source
   is already local, does nothing.  Returns an updated context."
  [ctx]
  (cond-> ctx
    (not-empty (get-in ctx [:build :git])) (download-git)))

;; Creates a runner fn according to its type
(defmulti make-runner :type)

(defmethod make-runner :child [_]
  (log/info "Using child process runner")
  (comp build-local download-src))

(defmethod make-runner :noop [_]
  ;; For testing
  (log/debug "No-op runner configured")
  (constantly 1))

(defmethod make-runner :default [_]
  ;; Fallback
  (log/warn "No runner configured, using fallback configuration")
  (constantly 2))
