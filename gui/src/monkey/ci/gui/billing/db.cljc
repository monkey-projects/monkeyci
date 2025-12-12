(ns monkey.ci.gui.billing.db
  (:require [monkey.ci.gui.loader :as lo]))

(def billing-id ::billing)

(defn set-invoicing-settings [db s]
  (lo/set-value db billing-id s))

(defn update-invoicing-settings [db f & args]
  (apply lo/update-value db billing-id f args))

(defn get-invoicing-settings [db]
  (lo/get-value db billing-id))

(defn set-billing-alerts [db a]
  (lo/set-alerts db billing-id a))

(defn get-billing-alerts [db]
  (lo/get-alerts db billing-id))

(defn set-billing-loading [db]
  (lo/set-loading db billing-id))

(defn billing-loading? [db]
  (lo/loading? db billing-id))
