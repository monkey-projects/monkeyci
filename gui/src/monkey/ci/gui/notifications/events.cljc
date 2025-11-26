(ns monkey.ci.gui.notifications.events
  (:require [monkey.ci.gui.martian]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.notifications.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::unregister-email
 (fn [{:keys [db]} [_ params]]
   {:dispatch [:martian.re-frame/request
               :unregister-email
               params
               [::unregister-email--success]
               [::unregister-email--failure]]
    :db (-> db
            (db/set-unregistering)
            (db/set-alerts []))}))

(rf/reg-event-db
 ::unregister-email--success
 (fn [db _]
   (db/reset-unregistering db)))

(rf/reg-event-db
 ::unregister-email--failure
 (fn [db [_ err]]
   (-> db
       (db/reset-unregistering)
       (db/set-alerts [(a/unregister-email-failed err)]))))
