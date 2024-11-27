(ns monkey.ci.runners.controller
  "Functions for running the application as a controller."
  (:require [babashka.fs :as fs]
            [monkey.ci.runners :as r]))

(def run-path (comp :run-path :config))

(defn- create-run-file [rt]
  (some-> (run-path rt)
          (fs/create-file))
  rt)

(defn- download-and-store-src [rt]
  (assoc rt :build (-> (:build rt)
                       (r/download-src rt)
                       (r/store-src rt))))

(defn- restore-build-cache [rt]
  ;; TODO
  rt)

(defn- save-build-cache [rt]
  ;; TODO
  rt)

(defn- wait-until-run-file-deleted [rt]
  (let [rp (run-path rt)]
    (while (fs/exists? rp)
      (Thread/sleep 500)))
  rt)

(defn run-controller [rt]
  (-> rt
      (download-and-store-src)
      (restore-build-cache)
      (create-run-file)
      (wait-until-run-file-deleted)
      (save-build-cache)))
