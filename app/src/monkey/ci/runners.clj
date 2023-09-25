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

(defn- get-absolute-dirs [{:keys [dir workdir]}]
  (let [wd (io/file (or workdir (u/cwd)))]
    {:script-dir (some->> dir
                          (u/abs-path wd)
                          (io/file)
                          (.getCanonicalPath))
     :work-dir (some-> wd (.getCanonicalPath))}))

(defn- script-not-found [{{:keys [script-dir]} :build}]
  (log/warn "No build script found at" script-dir)
  1)

(defn build-completed [{:keys [result exit] :as evt}]
  ;; Do some logging depending on the result
  (condp = (or result :unknown)
    :success (log/info "Success!")
    :warning (log/warn "Exited with warnings:" (:message evt))
    :error   (log/error "Failure.")
    :unknown (log/warn "Unknown result."))
  exit)

(defn build-local
  "Locates the build script locally and dispatches another event with the
   script details in them.  If no build script is found, dispatches a build
   complete event."
  [{:keys [args event-bus] :as ctx}]
  (let [{:keys [script-dir work-dir] :as build} (-> (get-absolute-dirs args)
                                                    (merge (select-keys args [:pipeline])))
        ctx (assoc ctx :build build)]
    (if (some-> (io/file script-dir) (.exists))
      (do
        (p/execute! ctx)
        (e/wait-for event-bus :build/completed (map build-completed)))
      (script-not-found ctx))))

;; Creates a runner fn according to its type
(defmulti make-runner :type)

(defmethod make-runner :child [_]
  build-local)

(defmethod make-runner :noop [_]
  ;; For testing
  (constantly 1))

(defmethod make-runner :default [_]
  ;; Fallback
  (constantly 2))
