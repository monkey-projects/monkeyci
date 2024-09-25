(ns monkey.ci.gui.artifact.db)

(defn set-downloading [db art-id]
  (assoc-in db [::artifacts :downloading art-id] true))

(defn reset-downloading [db art-id]
  (update-in db [::artifacts :downloading] dissoc art-id))

(defn downloading? [db art-id]
  (true? (get-in db [::artifacts :downloading art-id])))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))
