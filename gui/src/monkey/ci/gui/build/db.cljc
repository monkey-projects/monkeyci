(ns monkey.ci.gui.build.db
  (:require [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]))

(def id ::build-id)

(def params->sid (juxt :org-id :repo-id :build-id))

(def r->sid (comp params->sid r/path-params r/current))

(def get-id
  "Construct a unique loader id for the build using route params"
  (comp (partial vector id) r->sid))

(defn get-alerts [db]
  (lo/get-alerts db (get-id db)))

(defn set-alerts [db a]
  (lo/set-alerts db (get-id db) a))

(defn reset-alerts [db]
  (lo/reset-alerts db (get-id db)))

(defn get-build [db]
  (lo/get-value db (get-id db)))

(defn set-build [db b]
  (lo/set-value db (get-id db) b))

(defn update-build [db f & args]
  (apply lo/update-value db (get-id db) f args))

(def log-path ::log-path)

(defn set-log-path [db p]
  (assoc db log-path p))

(def log-alerts ::log-alerts)

(defn set-log-alerts [db a]
  (assoc db log-alerts a))

(defn reset-log-alerts [db]
  (dissoc db log-alerts))

(defn mark-canceling [db]
  (assoc-in db [(get-id db) ::canceling] true))

(defn reset-canceling [db]
  (update db (get-id db) dissoc ::canceling))

(defn canceling? [db]
  (true? (get-in db [(get-id db) ::canceling])))

(defn mark-retrying [db]
  (assoc-in db [(get-id db) ::retrying] true))

(defn reset-retrying [db]
  (update db (get-id db) dissoc ::retrying))

(defn retrying? [db]
  (true? (get-in db [(get-id db) ::retrying])))
