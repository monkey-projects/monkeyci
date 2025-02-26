(ns monkey.ci.runners.controller
  "Functions for running the application as a controller."
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [errors :as err]
             [process :as proc]
             [protocols :as p]
             [runners :as r]
             [script :as script]
             [utils :as u]]
            [monkey.ci.events.core :as ec]))

(def run-path (comp :run-path :config))
(def abort-path (comp :abort-path :config))
(def exit-path (comp :exit-path :config))
(def m2-cache-dir (comp :m2-cache-path :config))

(defn- post-events [rt evt]
  (ec/post-events (:events rt) evt))

(defn- post-init-evt
  "Post both build/start and script/initializing events.  Indicates the build has started,
   but the script is not running yet."
  [{:keys [build] :as rt}]
  (post-events rt [(b/build-start-evt build)
                   ;; TODO Remove this, script init should be fired in the script itself
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

(defmacro app-hash
  "Calculates the md5 hash for deps.edn at compile time."
  []
  (u/file-hash "deps.edn"))

(defn- save-build-cache [{:keys [build build-cache] :as rt}]
  (let [loc (repo-cache-location build)
        md (some-> (p/get-blob-info build-cache loc)
                   (deref)
                   :metadata)]
    ;; Only perform save if the hash has changed
    (if (not= (app-hash) (:app-hash md))
      (try
        (log/debug "Saving build cache for build" (b/sid build))
        @(blob/save build-cache
                    (m2-cache-dir rt)
                    loc
                    ;; TODO Include hash of the build deps.edn as well
                    {:app-hash (app-hash)})
        (catch Throwable ex
          (log/error "Failed to save build cache" ex)))
      (log/debug "Not saving build cache, hash is unchanged"))
    rt))

(defn- wait-until-run-file-deleted [rt]
  (let [rp (run-path rt)]
    (log/debug "Waiting until run file has been deleted at:" rp)
    (while (fs/exists? rp)
      (Thread/sleep 500))
    (log/debug "Run file deleted, build script finished."))
  rt)

(defn- post-end-evt [{:keys [build] :as rt}]
  (log/debug "Posting :build/end event for exit code" (:exit-code rt))
  (post-events rt (b/build-end-evt build (get rt :exit-code err/error-process-failure)))
  rt)

(defn- post-failure-evt [{:keys [build] :as rt} msg]
  (try
    (post-events rt (-> build
                        (mc/assoc-some :message msg)
                        (b/build-end-evt build err/error-process-failure)))
    (catch Exception ex
      (log/warn "Failed to post :build/end event" ex))))

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
        (save-build-cache)
        ;; Post the end event last, because it's the trigger for deleting the container.
        ;; This may cause builds to run slightly longer if the cache needs to be re-uploaded
        ;; so we may consider using the :script/end event to calculate credits instead.
        (post-end-evt)
        :exit-code)
    (catch Throwable ex
      (log/error "Failed to run controller" ex)
      (create-abort-file rt)
      (post-failure-evt rt (ex-message ex))
      err/error-process-failure)))
