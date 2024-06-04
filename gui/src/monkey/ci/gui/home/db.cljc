(ns monkey.ci.gui.home.db)

(def customers ::customers)

(defn set-customers [db c]
  (assoc db customers c))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))
