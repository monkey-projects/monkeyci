(ns monkey.ci.gui.dashboard.db
  (:require [monkey.ci.gui.loader :as lo]))

(def recent-builds ::recent-builds)

(defn set-recent-builds [db r]
  (lo/set-value db recent-builds r))
