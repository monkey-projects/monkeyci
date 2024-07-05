(ns monkey.ci.gui.params.db)

(def loading? ::loading)

(defn mark-loading [db]
  (assoc db loading? true))

(defn unmark-loading [db]
  (dissoc db loading?))

(def params ::params)

(defn set-params [db p]
  (assoc db params p))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))
