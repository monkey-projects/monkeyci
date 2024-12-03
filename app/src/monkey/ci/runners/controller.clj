(ns monkey.ci.runners.controller
  "Functions for running the application as a controller."
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [errors :as err]
             [process :as proc]
             [runners :as r]
             [script :as script]]
            [monkey.ci.events.core :as ec]))

(def run-path (comp :run-path :config))
(def abort-path (comp :abort-path :config))
(def exit-path (comp :exit-path :config))
(def m2-cache-dir (comp :m2-cache-path :config))

(defn- post-init-evt
  "Post both build/start and script/initializing events.  Indicates the build has started,
   but the script is not running yet."
  [{:keys [build] :as rt}]
  (ec/post-events (:events rt) [(b/build-start-evt build)
                                (script/script-init-evt build (b/script-dir build))])
  rt)

(defn- create-run-file [rt]
  (log/debug "Creating run file at:" (run-path rt))
  (some-> (run-path rt)
          (fs/create-file))
  rt)

(defn- create-abort-file [rt]
  (log/debug "Creating abort file at:" (abort-path rt))
  (some-> (abort-path rt)
          (fs/create-file))
  rt)

(defn- download-and-store-src [rt]
  (assoc rt :build (-> (:build rt)
                       (r/download-src rt)
                       (r/store-src rt))))

(defn- repo-cache-location
  "Returns the location to use in the repo cache for the given build."
  [build]
  (str (cs/join "/" (take 2 (b/sid build))) blob/extension))

(defn- restore-build-cache [{:keys [build build-cache] :as rt}]
  (log/debug "Restoring build cache for build" (b/sid build))
  ;; Restore to parent because the dir is in the archive
  @(blob/restore build-cache (repo-cache-location build) (str (fs/parent (m2-cache-dir rt))))
  rt)

(defn- save-build-cache [{:keys [build build-cache] :as rt}]
  ;; TODO Only do this if some hash value has changed
  (log/debug "Saving build cache for build" (b/sid build))
  (try
    ;; This results in class not found error?
    ;; Something with running a sub process in oci container instances?
    ;; We could run the script in a second container instead, similar to oci container jobs.
    @(blob/save build-cache (m2-cache-dir rt) (repo-cache-location build))
    (catch Throwable ex
      (log/error "Failed to save build cache" ex)))
  rt)

(defn- wait-until-run-file-deleted [rt]
  (let [rp (run-path rt)]
    (log/debug "Waiting until run file has been deleted at:" rp)
    (while (fs/exists? rp)
      (Thread/sleep 500))
    (log/debug "Run file deleted, build script finished."))
  rt)

(defn- post-end-evt [{:keys [build] :as rt}]
  (ec/post-events (:events rt) (b/build-end-evt build (get rt :exit-code err/error-process-failure)))
  rt)

(defn- read-exit-code [rt]
  (let [p (exit-path rt)
        exit-code (when (fs/exists? p)
                    (Integer/parseInt (.strip (slurp p))))]
    (cond-> rt
      exit-code (assoc :exit-code exit-code))))

(defn run-controller [rt]
  (try
    (-> rt
        (post-init-evt)
        (download-and-store-src)
        (restore-build-cache)
        (create-run-file)
        (wait-until-run-file-deleted)
        (read-exit-code)
        (post-end-evt)
        (save-build-cache)
        :exit-code)
    (catch Throwable ex
      (log/error "Failed to run controller" ex)
      (create-abort-file rt)
      err/error-process-failure)))
