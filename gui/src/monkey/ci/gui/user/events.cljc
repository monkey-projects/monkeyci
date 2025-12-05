(ns monkey.ci.gui.user.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.user.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::general-load
 (fn [{:keys [db]} [_ user-id]]
   {:dispatch [:secure-request
               :get-user-settings
               {:user-id user-id}
               [::general-load--success]
               [::general-load--failure]]
    :db (db/reset-general-alerts db)}))

(rf/reg-event-db
 ::general-load--success
 (fn [db [_ {settings :body}]]
   (db/set-user-settings db settings)))

(rf/reg-event-db
 ::general-load--failure
 (fn [db [_ err]]
   (db/set-general-alerts db [(a/user-general-load-failed err)])))

(rf/reg-event-db
 ::general-cancel
 (fn [db _]
   (-> db
       (db/reset-general-edit)
       (db/reset-general-saving))))

(rf/reg-event-db
 ::general-update
 (fn [db [_ p v]]
   (db/update-general-edit db assoc p v)))

(rf/reg-event-fx
 ::general-save
 (fn [{:keys [db]} _]
   (let [s (db/get-general-edit-merged db)]
     ;; TODO Also allow changing email address
     {:dispatch [:secure-request
                 :update-user-settings
                 {:user-id (:id s)
                  :settings s}
                 [::general-save--success]
                 [::general-save--failure]]
      :db (-> db
              (db/reset-general-alerts)
              (db/set-general-saving))})))

(rf/reg-event-db
 ::general-save--success
 (fn [db [_ {r :body}]]
   (-> db
       (db/set-user-settings r)
       (db/set-general-alerts [(a/user-general-save-success)])
       (db/reset-general-saving))))

(rf/reg-event-db
 ::general-save--failure
 (fn [db [_ err]]
   (-> db
       (db/set-general-alerts [(a/user-general-save-failed err)])
       (db/reset-general-saving))))
