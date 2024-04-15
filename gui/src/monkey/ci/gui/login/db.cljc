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

(def token :auth/token)

(defn set-token [db t]
  (assoc db token t))

(def github-token :github/token)

(defn set-github-token [db t]
  (assoc db github-token t))

(def github-config :auth/github-config)

(defn set-github-config [db c]
  (assoc db github-config c))

(def github-user ::github-user)

(defn set-github-user [db u]
  (assoc db github-user u))
