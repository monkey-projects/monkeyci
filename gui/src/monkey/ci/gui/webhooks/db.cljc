(ns monkey.ci.gui.webhooks.db
  (:require [monkey.ci.gui.loader :as l]))

(def id ::webhooks)

(defn get-webhooks [db]
  (l/get-value db id))

(defn set-webhooks [db w]
  (l/set-value db id w))

(defn set-alerts [db a]
  (l/set-alerts db id a))

(defn get-alerts [db]
  (l/get-alerts db id))

(defn loading? [db]
  (l/loading? db id))

(defn loaded? [db]
  (l/loaded? db id))

(defn set-new [db e]
  (assoc db ::new e))

(defn reset-new [db]
  (dissoc db ::new))

(def get-new ::new)

(def creating? ::creating?)

(defn set-creating [db]
  (assoc db ::creating? true))

(defn reset-creating [db]
  (dissoc db ::creating?))
