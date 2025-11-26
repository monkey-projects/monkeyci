(ns monkey.ci.gui.notifications.db)

(def unregistering? ::unregistering)

(defn set-unregistering [db]
  (assoc db unregistering? true))

(defn reset-unregistering [db]
  (dissoc db unregistering?))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db ::alerts a))
