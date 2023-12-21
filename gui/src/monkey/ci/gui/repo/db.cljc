(ns monkey.ci.gui.repo.db)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))

(def builds ::builds)

(defn set-builds [db b]
  (assoc db builds b))
