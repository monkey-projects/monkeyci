(ns monkey.ci.gui.admin.login.events
  (:require [monkey.ci.gui.admin.login.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.martian]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :login/submit
 (fn [{:keys [db]} [_ {:keys [username password]}]]
   {:dispatch [:secure-request
               :admin-login
               {:creds {:username (first username)
                        :password (first password)}}
               [:login/submit--success]
               [:login/submit--failed]]
    :db (-> db
            (db/mark-submitting)
            (ldb/clear-alerts))}))

(rf/reg-event-fx
 :login/submit--success
 (fn [{:keys [db]} [_ {user :body}]]
   ;; TODO Redirect to admin root
   {:db (-> db
            (ldb/set-user (dissoc user :token))
            (ldb/set-token (:token user)))}))

(rf/reg-event-db
 :login/submit--failed
 (fn [db [_ err]]
   (ldb/set-alerts db [(a/admin-login-failed err)])))
