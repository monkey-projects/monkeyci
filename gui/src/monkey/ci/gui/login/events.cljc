(ns monkey.ci.gui.login.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.apis.bitbucket :as bitbucket]
            [monkey.ci.gui.apis.github :as github]
            [monkey.ci.gui.apis.codeberg :as codeberg]
            [monkey.ci.gui.local-storage :as ls]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def storage-redir-id db/storage-redir-id)
(def storage-token-id db/storage-token-id)

(rf/reg-event-fx
 :login/login-and-redirect
 (fn [{:keys [db]} _]
   (let [cr (r/current db)
         next-route (:path cr)]
     {:local-storage [storage-redir-id
                      ;; Ignore public routes to avoid redirecting to the callback pages
                      (when-not (r/public? (r/route-name cr))
                        {:redirect-to next-route})]
      :dispatch [:route/goto :page/login]})))

(rf/reg-event-db
 :login/submit
 (fn [db [_ fd]]
   (db/set-submitting db)))

(rf/reg-event-fx
 :login/github-code-received
 (fn [{:keys [db]} [_ code]]
   {:dispatch [:martian.re-frame/request
               :github-login
               {:code code}
               [:login/github-login--success]
               [:login/github-login--failed]]
    :db (-> db
            (db/clear-alerts)
            (db/set-user nil))}))

(defn try-load-github-user
  "Adds a request to the fx to load github user details, if a token is provided."
  [{:keys [db] :as fx} github-token]
  (cond-> fx
    github-token (assoc :http-xhrio
                        (github/api-request
                         db
                         {:method :get
                          :path "/user"
                          :token github-token
                          :on-success [:github/load-user--success]
                          :on-failure [:github/load-user--failed]}))))

(rf/reg-event-fx
 :login/github-login--success
 (fn [{:keys [db local-storage]} [_ {{:keys [github-token] :as u} :body}]]
   (log/debug "Got user details:" (clj->js u))
   (-> {:db (-> db
                (db/set-user (dissoc u :token :github-token))
                (db/set-token (:token u))
                (db/set-github-token github-token))
        ;; Store full user details locally, so we can retrieve them on page reload without having
        ;; to re-authenticate.
        :local-storage [storage-token-id u]}
       (try-load-github-user github-token))))

(defn- redirect-evt
  "Constructs the event to dispatch to redirect to the desired destination after login."
  [user local-storage]
  (let [redir (:redirect-to local-storage)]
    (log/debug "Redirect route:" redir)
    (cond
      (and redir (not= "/" redir))
      ;; If a redirect path was stored, go there
      [:route/goto-path redir]
      ;; If the user only has one org, go directly there
      (= 1 (count (:orgs user)))
      [:route/goto :page/org {:org-id (first (:orgs user))}]
      ;; Any other case, go to the root page
      :else
      [:route/goto :page/root])))

(rf/reg-event-fx
 :github/load-user
 (fn [{:keys [db] :as ctx} _]
   (when-let [token (db/provider-token db)]
     (try-load-github-user ctx token))))

(rf/reg-event-fx
 :github/load-user--success
 [(rf/inject-cofx :local-storage storage-redir-id)]
 (fn [{:keys [db local-storage]} [_ github-user]]
   (log/debug "Github user loaded, redirecting to page as stored locally")
   {:db (db/set-github-user db github-user)
    :dispatch (redirect-evt (db/user db) local-storage)
    :local-storage [storage-redir-id (dissoc local-storage :redirect-to)]}))

(rf/reg-event-fx
 :github/load-user--failed
 [(rf/inject-cofx :local-storage storage-token-id)]
 (fn [{:keys [db] :as ctx} [_ err]]
   (log/warn "Unable to load github user:" (str err))
   (let [rt (get-in ctx [:local-storage :refresh-token])]
     (if (and (= 401 (:status err)) rt)
       {:dispatch [:monkey.ci.gui.martian/refresh-token rt [:github/load-user]]}
       {:db (db/set-alerts db [(a/github-load-user-failed err)])}))))

(rf/reg-event-db
 :login/github-login--failed
 (fn [db [_ err]]
   (db/set-alerts db [(a/github-login-failed err)])))

(rf/reg-event-fx
 :login/load-github-config
 (fn [{:keys [db]} [_ code]]
   {:dispatch [:martian.re-frame/request
               :get-github-config
               {}
               [:login/load-github-config--success]
               [:login/load-github-config--failed]]}))

(rf/reg-event-db
 :login/load-github-config--success
 (fn [db [_ {config :body :as resp}]]
   (db/set-github-config db config)))

(rf/reg-event-db
 :login/load-github-config--failed
 (fn [db [_ err]]
   (db/set-alerts db
                  [(a/github-load-config-failed err)])))

(rf/reg-event-fx
 :login/load-bitbucket-config
 (fn [{:keys [db]} [_ code]]
   {:dispatch [:martian.re-frame/request
               :get-bitbucket-config
               {}
               [:login/load-bitbucket-config--success]
               [:login/load-bitbucket-config--failed]]}))

(rf/reg-event-db
 :login/load-bitbucket-config--success
 (fn [db [_ {config :body}]]
   (db/set-bitbucket-config db config)))

(rf/reg-event-db
 :login/load-bitbucket-config--failed
 (fn [db [_ err]]
   (db/set-alerts db
                  [(a/bitbucket-load-config-failed err)])))

(rf/reg-event-fx
 :login/bitbucket-code-received
 (fn [{:keys [db]} [_ code]]
   {:dispatch [:martian.re-frame/request
               :bitbucket-login
               {:code code}
               [:login/bitbucket-login--success]
               [:login/bitbucket-login--failed]]
    :db (-> db
            (db/clear-alerts)
            (db/set-user nil))}))

(defn try-load-bitbucket-user [{:keys [db] :as fx} bitbucket-token]
  (cond-> fx
    bitbucket-token (assoc :http-xhrio
                           (bitbucket/api-request
                            db
                            {:method :get
                             :path "/user"
                             :token bitbucket-token
                             :on-success [:bitbucket/load-user--success]
                             :on-failure [:bitbucket/load-user--failed]}))))

(rf/reg-event-fx
 :login/bitbucket-login--success
 (fn [{:keys [db local-storage]} [_ {{:keys [bitbucket-token] :as u} :body}]]
   (-> {:db (-> db
                (db/set-user (dissoc u :token :bitbucket-token))
                (db/set-token (:token u))
                (db/set-bitbucket-token bitbucket-token))
        ;; Store full user details locally, so we can retrieve them on page reload without having
        ;; to re-authenticate.
        :local-storage [storage-token-id u]}
       (try-load-bitbucket-user bitbucket-token))))

(rf/reg-event-fx
 :bitbucket/load-user--success
 [(rf/inject-cofx :local-storage storage-redir-id)]
 (fn [{:keys [db local-storage]} [_ bitbucket-user]]
   (let [redir (:redirect-to local-storage)]
     (log/debug "Bitbucket user details:" #?(:cljs (clj->js bitbucket-user)
                                             :clj bitbucket-user))
     {:db (db/set-bitbucket-user db bitbucket-user)
      :dispatch (redirect-evt (db/user db) local-storage)
      :local-storage [storage-redir-id (dissoc local-storage :redirect-to)]})))

(rf/reg-event-db
 :bitbucket/load-user--failed
 (fn [db [_ err]]
   (db/set-alerts db [(a/bitbucket-load-user-failed err)])))

(rf/reg-event-db
 :login/bitbucket-login--failed
 (fn [db [_ err]]
   (db/set-alerts db [(a/bitbucket-login-failed err)])))

(rf/reg-event-fx
 :login/load-codeberg-config
 (fn [{:keys [db]} [_ code]]
   {:dispatch [:martian.re-frame/request
               :get-codeberg-config
               {}
               [:login/load-codeberg-config--success]
               [:login/load-codeberg-config--failed]]}))

(rf/reg-event-db
 :login/load-codeberg-config--success
 (fn [db [_ {config :body :as resp}]]
   (db/set-codeberg-config db config)))

(rf/reg-event-db
 :login/load-codeberg-config--failed
 (fn [db [_ err]]
   (db/set-alerts db
                  [(a/codeberg-load-config-failed err)])))

(rf/reg-event-fx
 :login/codeberg-code-received
 (fn [{:keys [db]} [_ code]]
   {:dispatch [:martian.re-frame/request
               :codeberg-login
               {:code code}
               [:login/codeberg-login--success]
               [:login/codeberg-login--failed]]
    :db (-> db
            (db/clear-alerts)
            (db/set-user nil))}))


(defn try-load-codeberg-user
  "Adds a request to the fx to load codeberg user details, if a token is provided."
  [{:keys [db] :as fx} codeberg-token]
  (cond-> fx
    codeberg-token (assoc :http-xhrio
                          (codeberg/api-request
                           db
                           {:method :get
                            :path "/login/oauth/userinfo"
                            :token codeberg-token
                            :on-success [:codeberg/load-user--success]
                            :on-failure [:codeberg/load-user--failed]}))))

(rf/reg-event-fx
 :codeberg/load-user--success
 [(rf/inject-cofx :local-storage storage-redir-id)]
 (fn [{:keys [db local-storage]} [_ codeberg-user]]
   (log/debug "Codeberg user loaded, redirecting to page as stored locally")
   {:db (db/set-codeberg-user db codeberg-user)
    :dispatch (redirect-evt (db/user db) local-storage)
    :local-storage [storage-redir-id (dissoc local-storage :redirect-to)]}))

(rf/reg-event-fx
 :codeberg/load-user--failed
 [(rf/inject-cofx :local-storage storage-token-id)]
 (fn [{:keys [db] :as ctx} [_ err]]
   (log/warn "Unable to load codeberg user:" (str err))
   (let [rt (get-in ctx [:local-storage :refresh-token])]
     (if (and (= 401 (:status err)) rt)
       {:dispatch [:monkey.ci.gui.martian/refresh-token rt [:codeberg/load-user]]}
       {:db (db/set-alerts db [(a/codeberg-load-user-failed err)])}))))

(rf/reg-event-fx
 :login/codeberg-login--success
 (fn [{:keys [db local-storage]} [_ {{:keys [codeberg-token] :as u} :body}]]
   (-> {:db (-> db
                (db/set-user (dissoc u :token :codeberg-token))
                (db/set-token (:token u))
                (db/set-codeberg-token codeberg-token))
        ;; Store full user details locally, so we can retrieve them on page reload without having
        ;; to re-authenticate.
        :local-storage [storage-token-id u]}
       (try-load-codeberg-user codeberg-token))))

(rf/reg-event-db
 :login/codeberg-login--failed
 (fn [db [_ err]]
   (db/set-alerts db [(a/codeberg-login-failed err)])))

(rf/reg-event-fx
 :login/sign-off
 (fn [{:keys [db]} _]
   {:db (-> db
            (db/set-user nil)
            (db/set-token nil))
    :dispatch [:route/goto :page/login]
    ;; Clear stored tokens
    :local-storage [storage-token-id nil]}))
