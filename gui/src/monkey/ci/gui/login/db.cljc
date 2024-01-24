(ns monkey.ci.gui.login.db)

(defn submitting? [db]
  (true? (::submitting? db)))

(defn set-submitting [db]
  (assoc db ::submitting? true))

(defn unset-submitting [db]
  (dissoc db ::submitting?))

(defn set-user [db u]
  (assoc db ::user u))

(def user ::user)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))
