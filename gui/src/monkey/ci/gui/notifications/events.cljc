(ns monkey.ci.gui.notifications.events
  (:require [monkey.ci.gui.martian]
            [monkey.ci.gui.notifications.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :notifications/unregister-email
 (fn [{:keys [db]} [_ params]]
   {:dispatch [:secure-request
               :unregister-email
               params
               [:notifications/unregister-email--success]
               [:notifications/unregister-email--failure]]
    :db (db/set-unregistering db)}))
