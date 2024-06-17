(ns monkey.ci.gui.login.events
  (:require [monkey.ci.gui.github :as github]
            [monkey.ci.gui.local-storage :as ls]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def storage-redir-id "login-redir")
(def storage-token-id "login-tokens")

(rf/reg-event-fx
 :login/login-and-redirect
 (fn [{:keys [db]} _]
   (let [next-route (:path (r/current db))]
     {:local-storage [storage-redir-id {:redirect-to next-route}]
      :dispatch [:route/goto :page/login]})))

(rf/reg-event-db
 :login/submit
 (fn [db [_ fd]]
   (db/set-submitting db)))

(rf/reg-event-db
 :login/authenticated
 (fn [db [_ user]]
   (-> db
       (db/set-user user)
       (db/unset-submitting))))

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

(rf/reg-event-fx
 :login/github-login--success
 (fn [{:keys [db local-storage]} [_ {{:keys [github-token] :as u} :body}]]
   (log/debug "Got user details:" (clj->js u))
   {:db (-> db
            (db/set-user (dissoc u :token :github-token))
            (db/set-token (:token u))
            (db/set-github-token github-token))
    :http-xhrio (github/api-request
                 db
                 {:method :get
                  :path "/user"
                  :token github-token
                  :on-success [:github/load-user--success]
                  :on-failure [:github/load-user--failed]})
    :local-storage [storage-token-id (select-keys u [:github-token :token])]}))

(rf/reg-event-fx
 :github/load-user--success
 [(rf/inject-cofx :local-storage storage-redir-id)]
 (fn [{:keys [db local-storage]} [_ github-user]]
   (let [redir (:redirect-to local-storage)
         u (db/user db)]
     (log/debug "Redirect route:" redir)
     (log/debug "Github user details:" #?(:cljs (clj->js github-user)
                                          :clj github-user))
     {:db (db/set-github-user db github-user)
      :dispatch (cond
                  (and redir (not= "/" redir))
                  ;; If a redirect path was stored, go there
                  [:route/goto-path redir]
                  ;; If the user only has one customer, go directly there
                  (= 1 (count (:customers u)))
                  [:route/goto :page/customer {:customer-id (first (:customers u))}]
                  ;; Any other case, go to the root page
                  :else
                  [:route/goto :page/root])
      :local-storage [storage-redir-id (dissoc local-storage :redirect-to)]})))

(rf/reg-event-db
 :github/load-user--failed
 (fn [db [_ err]]
   (db/set-alerts db [{:type :danger
                       :message (str "Unable to retrieve user details from Github: " (u/error-msg err))}])))

(rf/reg-event-db
 :login/github-login--failed
 (fn [db [_ err]]
   (db/set-alerts db [{:type :danger
                       :message (str "Unable to fetch Github user token: " (u/error-msg err))}])))

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
 (fn [db [_ {config :body}]]
   (db/set-github-config db config)))

(rf/reg-event-db
 :login/load-github-config--failed
 (fn [db [_ err]]
   (db/set-alerts db
                  [{:type :danger
                    :message (str "Unable to load Github config:" (u/error-msg err))}])))

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
                  [{:type :danger
                    :message (str "Unable to load BitBucket config:" (u/error-msg err))}])))

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

(rf/reg-event-fx
 :login/bitbucket-login--success
 (fn [{:keys [db local-storage]} [_ {{:keys [bitbucket-token] :as u} :body}]]
   {:db (-> db
            (db/set-user (dissoc u :token :bitbucket-token))
            (db/set-token (:token u))
            (db/set-bitbucket-token bitbucket-token))
    ;; TODO
    ;; :http-xhrio (bitbucket/api-request
    ;;              db
    ;;              {:method :get
    ;;               :path "/user"
    ;;               :token bitbucket-token
    ;;               :on-success [:bitbucket/load-user--success]
    ;;               :on-failure [:bitbucket/load-user--failed]})
    :local-storage [storage-token-id (select-keys u [:bitbucket-token :token])]}))

(rf/reg-event-db
 :login/bitbucket-login--failed
 (fn [db [_ err]]
   (db/set-alerts db [{:type :danger
                       :message (str "Unable to fetch Bitbucket user token: " (u/error-msg err))}])))

(rf/reg-event-fx
 :login/sign-off
 (fn [{:keys [db]} _]
   {:db (-> db
            (db/set-user nil)
            (db/set-token nil))
    :dispatch [:route/goto :page/login]
    ;; Clear stored tokens
    :local-storage [storage-token-id nil]}))
