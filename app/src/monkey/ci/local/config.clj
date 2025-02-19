(ns monkey.ci.local.config
  "Configuration management functions for local builds"
  (:require [babashka.fs :as fs]))

(def get-work-dir "Retrieves working directory from config"
  :work-dir)

(defn set-work-dir [conf wd]
  (assoc conf :work-dir wd))

(defn get-workspace [conf]
  (fs/path (get-work-dir conf) "workspace"))
