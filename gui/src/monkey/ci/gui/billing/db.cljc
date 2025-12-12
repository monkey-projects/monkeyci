(ns monkey.ci.gui.billing.db
  (:require [monkey.ci.gui.loader :as lo]))

(def billing-id ::billing)

(defn set-invoicing-settings [db s]
  (lo/set-value db billing-id s))

(defn get-invoicing-settings [db]
  (lo/get-value db billing-id))
