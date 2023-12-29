(ns monkey.ci.gui.repo.db)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))

(def builds ::builds)

(defn set-builds [db b]
  (assoc db builds b))

(def latest-build ::latest-build)

(defn set-latest-build [db b]
  (assoc db latest-build b))
