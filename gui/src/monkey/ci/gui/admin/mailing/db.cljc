(ns monkey.ci.gui.admin.mailing.db
  (:require [monkey.ci.gui.loader :as l]))

(def mailing-id ::id)

(defn get-mailings [db]
  (l/get-value db mailing-id))

(defn set-mailings [db m]
  (l/set-value db mailing-id m))
