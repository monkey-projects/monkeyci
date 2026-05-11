(ns monkey.ci.cli.config
  "Configuration management functions for local builds"
  (:require [babashka.fs :as fs]
            [monkey.ci.cli.version :as v]))

(def empty-config {})

(def get-work-dir "Retrieves working directory from config"
  :work-dir)

(defn set-work-dir [conf wd]
  (assoc conf :work-dir wd))

(defn- work-path [conf dir]
  (fs/path (get-work-dir conf) dir))

(defn get-workspace [conf]
  (work-path conf "workspace"))

(defn get-artifact-dir [conf]
  (work-path conf "artifacts"))

(defn get-cache-dir [conf]
  (work-path conf "cache"))

(defn get-log-dir [conf]
  (work-path conf "logs"))

(defn get-jobs-dir [conf]
  (work-path conf "jobs"))

(def get-build :build)

(defn set-build [conf b]
  (assoc conf :build b))

(def get-params :params)

(defn set-params [conf p]
  (assoc conf :params p))

(def get-quiet :quiet)

(defn set-quiet [conf v]
  (assoc conf :quiet v))

(def get-job-filter :filter)

(defn set-job-filter [conf f]
  (assoc conf :filter f))

(def get-ending
  "Retrieves the result promise, that is used to pass build result to the caller."
  :ending)

(defn set-ending [conf r]
  (assoc conf :ending r))

(def get-api "Configuration to access build api" :api)

(defn set-api [conf api]
  (assoc conf :api api))

(def get-global-api "Configuration to access global api" :global-api)

(defn set-global-api [conf api]
  (assoc conf :global-api api))

(defn get-lib-coords [ctx]
  (get ctx :lib-coords {:mvn/version (v/version)}))

(defn set-lib-coords [conf lib-coords]
  (assoc conf :lib-coords lib-coords))

(def get-log-config :log-config)

(defn set-log-config [conf p]
  (assoc conf :log-config p))

(def get-m2-cache-dir :m2-cache-dir)

(defn set-m2-cache-dir [conf p]
  (assoc conf :m2-cache-dir p))

(def get-no-clean
  "Returns true if the workspace should NOT be deleted after the build completes."
  :no-clean)

(defn set-no-clean [conf v]
  (assoc conf :no-clean v))

(defn get-child-opts [conf]
  (select-keys conf [:lib-coords :log-config :m2-cache-dir]))

(def get-podman
  "Returns the podman configuration map (e.g. {:podman-cmd \"podman\",
   :expose-ports [20000 21000]})."
  :podman)

(defn set-podman [conf opts]
  (assoc conf :podman opts))
