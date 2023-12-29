(ns monkey.ci.gui.build.db)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))

(def logs ::logs)

(defn set-logs [db l]
  (assoc db logs l))
