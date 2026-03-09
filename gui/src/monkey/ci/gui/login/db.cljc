(ns monkey.ci.gui.login.db)

(def storage-redir-id "login-redir")
(def storage-token-id "login-tokens")

(defn submitting? [db]
  (true? (::submitting? db)))

(defn set-submitting [db]
  (assoc db ::submitting? true))

(defn unset-submitting [db]
  (dissoc db ::submitting?))

(defn set-user [db u]
  (println "Setting user:" (str u))
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

(def provider-auth ::provider-auth)

(defn set-provider-auth [db pa]
  (assoc db provider-auth pa))

(def provider (comp :provider provider-auth))

(def provider-token (comp :token provider-auth))

(defn set-provider-token [db t]
  (assoc-in db [provider-auth :token] t))

(defn- reset-token [db provider t]
  (cond-> db
    t (set-provider-auth {:provider provider
                          :token t})))

(def github-token provider-token)

(defn set-github-token [db t]
  (reset-token db :github t))

(def bitbucket-token provider-token)

(defn set-bitbucket-token [db t]
  (reset-token db :bitbucket t))

(def codeberg-token provider-token)

(defn set-codeberg-token [db t]
  (reset-token db :codeberg t))

(def github-config :auth/github-config)

(defn set-github-config [db c]
  (assoc db github-config c))

(def bitbucket-config :auth/bitbucket-config)

(defn set-bitbucket-config [db c]
  (assoc db bitbucket-config c))

(def codeberg-config :auth/codeberg-config)

(defn set-codeberg-config [db c]
  (assoc db codeberg-config c))

(def github-user ::github-user)

(defn set-github-user [db u]
  (assoc db github-user u))

(def bitbucket-user ::bitbucket-user)

(defn set-bitbucket-user [db u]
  (assoc db bitbucket-user u))

(def codeberg-user ::codeberg-user)

(defn set-codeberg-user [db u]
  (assoc db codeberg-user u))
