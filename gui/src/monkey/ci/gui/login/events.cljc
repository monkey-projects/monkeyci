(ns monkey.ci.gui.login.events
  (:require [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

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
 (fn [{:keys [db]} [_ {u :body}]]
   (log/debug "Got user details:" u)
   {:db (-> db
            (db/set-user (dissoc u :token))
            (db/set-token (:token u)))
    :dispatch [:route/goto :page/root]}))

(rf/reg-event-db
 :login/github-login--failed
 (fn [db [_ err]]
   (log/debug "Got error:" err)
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
   (log/debug "Got github config:" config)
   (db/set-github-config db config)))

(rf/reg-event-db
 :login/load-github-config--failed
 (fn [db [_ err]]
   ;; Nothing (yet?)
   (log/warn "Unable to load github config:" err)
   db))
