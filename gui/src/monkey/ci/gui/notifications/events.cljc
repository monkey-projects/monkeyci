(ns monkey.ci.gui.notifications.events
  (:require [monkey.ci.gui.martian]
            [monkey.ci.gui.notifications.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :notifications/unregister-email
 (fn [{:keys [db]} [_ id]]
   {:dispatch [:secure-request
               :delete-email-reg
               {:registration-id id}
               [:notifications/unregister-email--success]
               [:notifications/unregister-email--failure]]
    :db (db/set-unregistering db)}))
