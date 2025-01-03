(ns monkey.ci.gui.admin.login.events
  (:require [monkey.ci.gui.admin.login.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::submit
 (fn [{:keys [db]} [_ {:keys [username password]}]]
   {:dispatch [:martian.re-frame/request
               :admin-login
               {:creds {:username (first username)
                        :password (first password)}}
               [::submit--success]
               [::submit--failed]]
    :db (-> db
            (db/mark-submitting)
            (ldb/clear-alerts))}))

(rf/reg-event-fx
 ::submit--success
 (fn [{:keys [db]} [_ {user :body}]]
   {:db (-> db
            (ldb/set-user (dissoc user :token))
            (ldb/set-token (:token user)))
    ;; Redirect to admin root page
    :dispatch [:route/goto :admin/root]}))

(rf/reg-event-db
 ::submit--failed
 (fn [db [_ err]]
   (log/warn "Login failed:" (str err))
   (ldb/set-alerts db [(a/admin-login-failed err)])))
