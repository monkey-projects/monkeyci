(ns monkey.ci.runners.controller
  "Functions for running the application as a controller."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [build :as b]
             [errors :as err]
             [runners :as r]
             [script :as script]]
            [monkey.ci.events.core :as ec]))

(def run-path (comp :run-path :config))
(def abort-path (comp :abort-path :config))
(def exit-path (comp :exit-path :config))
(def m2-cache-dir (comp :m2-cache-path :config))

(defn- post-init-evt [{:keys [build] :as rt}]
  (ec/post-events (:events rt) (script/script-init-evt build (b/script-dir build)))
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

(defn- restore-build-cache [{:keys [build build-cache] :as rt}]
  ;; TODO
  rt)

(defn- save-build-cache [rt]
  ;; TODO
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

