(ns monkey.ci.gui.admin.invoicing.db
  (:require [monkey.ci.gui.loader :as lo]))

(def id ::invoices)

(defn get-invoices [db]
  (lo/get-value db id))

(defn set-invoices [db i]
  (lo/set-value db id i))

(defn loading? [db]
  (lo/loading? db id))

(defn set-alerts [db a]
  (lo/set-alerts db id a))

(defn get-alerts [db]
  (lo/get-alerts db id))
