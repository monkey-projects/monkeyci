(ns monkey.ci.gui.build.db
  (:require [monkey.ci.gui.loader :as lo]))

(def id ::build-id)

(defn get-alerts [db]
  (lo/get-alerts db id))

(defn set-alerts [db a]
  (lo/set-alerts db id a))

(defn reset-alerts [db]
  (lo/reset-alerts db id))

(defn get-build [db]
  (lo/get-value db id))

(defn set-build [db b]
  (lo/set-value db id b))

(defn update-build [db f & args]
  (apply lo/update-value db id f args))

(def log-path ::log-path)

(defn set-log-path [db p]
  (assoc db log-path p))

(def log-alerts ::log-alerts)

(defn set-log-alerts [db a]
  (assoc db log-alerts a))

(defn reset-log-alerts [db]
  (dissoc db log-alerts))
