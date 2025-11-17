(ns monkey.ci.gui.admin.mailing.events
  (:require [monkey.ci.gui.admin.mailing.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::cancel-edit
 (fn [{:keys [db]} _]
   {:dispatch [:route/goto :admin/emails]
    :db (-> db
            (db/reset-editing)
            (db/reset-saving))}))

(rf/reg-event-fx
 ::save-mailing
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :admin-create-mailing
               {:mailing (db/get-editing db)}
               [::save-mailing--success]
               [::save-mailing--failure]]
    :db (-> db
            (db/mark-saving)
            (db/reset-editing-alerts))}))

(rf/reg-event-db
 ::save-mailing--success
 (fn [db [_ reply]]
   (-> db
       (db/reset-saving)
       (db/update-mailing (:body reply)))))

(rf/reg-event-db
 ::save-mailing--failure
 (fn [db [_ err]]
   (-> db
       (db/set-editing-alerts [(a/save-mailing-failed err)])
       (db/reset-saving))))

(rf/reg-event-db
 ::edit-prop-changed
 (fn [db [_ prop v]]
   (db/update-editing db assoc prop v)))
