(ns monkey.ci.gui.admin.clean.db
  (:require [monkey.ci.gui.loader :as lo]))

(def clean ::clean)

(defn get-cleaned-processes [db]
  (lo/get-value db clean))

(defn set-cleaned-processes [db p]
  (lo/set-value db clean p))

(defn get-alerts [db]
  (lo/get-alerts db clean))

(defn cleaned? [db]
  (lo/loaded? db clean))

(defn cleaning? [db]
  (lo/loading? db clean))
