(ns monkey.ci.gui.login.events
  (:require [monkey.ci.gui.login.db :as db]
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
               [:login/github-code-received--success]
               [:login/github-code-received--failed]]
    :db (-> db
            (db/clear-alerts)
            (db/set-user nil))}))

(rf/reg-event-fx
 :login/github-code-received--success
 (fn [{:keys [db]} [_ {u :body}]]
   (println "Got user details:" u)
   {:db (db/set-user db u)
    :dispatch [:route/goto :page/root]}))

(rf/reg-event-db
 :login/github-code-received--failed
 (fn [db [_ err]]
   (db/set-alerts db [{:type :danger
                       :message (str "Unable to fetch Github user token: " (u/error-msg err))}])))
