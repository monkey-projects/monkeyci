(ns monkey.ci.gui.notifications.db)

(def unregistering? ::unregistering)

(defn set-unregistering [db]
  (assoc db unregistering? true))

(defn reset-unregistering [db]
  (dissoc db unregistering?))
