(ns monkey.ci.gui.job.db
  (:require [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]))

(def route->id (comp (juxt :customer-id :repo-id :build-id :job-id)
                     r/path-params))
(def db->job-id (comp route->id r/current))

(def get-id db->job-id)

(defn get-path-id [db path]
  (conj (db->job-id db) path))

(defn get-alerts
  ([db path]
   (lo/get-alerts db (get-path-id db path)))
  ([db]
   (lo/get-alerts db (db->job-id db))))

(defn set-alerts
  ([db path a]
   (lo/set-alerts db (get-path-id db path) a))
  ([db a]
   (lo/set-alerts db (db->job-id db) a)))

(defn clear-alerts
  ([db path]
   (lo/reset-alerts db (get-path-id db path)))
  ([db]
   (lo/reset-alerts db (db->job-id db))))

(defn path-alerts [db path]
  (get-alerts db path))

(defn global-alerts [db]
  (get-alerts db))

(defn get-logs [db path]
  (lo/get-value db (get-path-id db path)))

(defn set-logs [db path l]
  (lo/set-value db (get-path-id db path) l))

(defn logs-loading? [db path]
  (lo/loading? db (get-path-id db path)))

(def log-files ::log-files)

(defn set-log-files [db l]
  (assoc db log-files l))

(defn clear-log-files [db]
  (dissoc db log-files))

(defn set-log-expanded [db idx exp?]
  (assoc-in db [::expanded idx] exp?))

(defn log-expanded?
  ([db idx]
   (true? (get-in db [::expanded idx])))
  ([db]
   (::expanded db)))

(defn clear-expanded [db]
  (dissoc db ::expanded))
