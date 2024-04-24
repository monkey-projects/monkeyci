(ns monkey.ci.gui.job.db)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))
