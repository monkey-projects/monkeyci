(ns monkey.ci.gui.webhooks.db
  (:require [monkey.ci.gui.loader :as l]))

(def id ::webhooks)

(defn get-webhooks [db]
  (l/get-value db id))

(defn set-webhooks [db w]
  (l/set-value db id w))
