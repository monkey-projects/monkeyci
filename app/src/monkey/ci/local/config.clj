(ns monkey.ci.local.config
  "Configuration management functions for local builds"
  (:require [babashka.fs :as fs]))

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

(def get-build :build)

(defn set-build [conf b]
  (assoc conf :build b))

(def get-params :params)

(defn set-params [conf p]
  (assoc conf :params p))
