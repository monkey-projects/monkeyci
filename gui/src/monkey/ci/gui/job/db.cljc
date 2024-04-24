(ns monkey.ci.gui.job.db)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))

(def logs ::logs)

(defn set-logs [db l]
  (assoc db logs l))
