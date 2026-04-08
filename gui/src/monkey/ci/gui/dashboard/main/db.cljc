(ns monkey.ci.gui.dashboard.main.db
  (:require [monkey.ci.gui.loader :as lo]))

(def get-assets-url ::assets-url)

(defn set-assets-url [db u]
  (assoc db ::assets-url u))

(defn set-metrics [db id v]
  (assoc-in db [::metrics id] v))

(defn get-metrics [db id]
  (get-in db [::metrics id]))

(def recent-builds ::recent-builds)

(defn get-recent-builds [db]
  (lo/get-value db recent-builds))

(defn set-recent-builds [db a]
  (lo/set-value db recent-builds a))
