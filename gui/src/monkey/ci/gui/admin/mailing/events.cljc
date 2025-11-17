(ns monkey.ci.gui.admin.mailing.events
  (:require [monkey.ci.gui.admin.mailing.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::load-mailings
 (lo/loader-evt-handler
  db/mailing-id
  (fn [_ _ _]
    [:secure-request
     :admin-get-mailings
     {}
     [::load-mailings--success]
     [::load-mailings--failure]])))

(rf/reg-event-db
 ::load-mailings--success
 (fn [db [_ resp]]
   (lo/on-success db db/mailing-id resp)))

(rf/reg-event-db
 ::load-mailings--failure
 (fn [db [_ resp]]
   (lo/on-failure db db/mailing-id a/mailing-load-failed resp)))

(rf/reg-event-fx
 ::cancel-edit
 (fn [{:keys [db]} _]
   {:dispatch [:route/goto :admin/mailings]
    :db (-> db
            (db/reset-editing)
            (db/reset-saving))}))

(rf/reg-event-fx
 ::save-mailing
 (fn [{:keys [db]} _]
   (let [m (db/get-editing db)
         new? (nil? (:id m))]
     {:dispatch [:secure-request
                 (if new? :admin-create-mailing :admin-update-mailing)
                 (cond-> {:mailing m}
                   (not new?) (assoc :mailing-id (:id m)))
                 [::save-mailing--success]
                 [::save-mailing--failure]]
      :db (-> db
              (db/mark-saving)
              (db/reset-editing-alerts))})))

(rf/reg-event-fx
 ::save-mailing--success
 (fn [{:keys [db]} [_ {m :body}]]
   {:db (-> db
            (db/reset-saving)
            (db/update-mailing m)
            (db/set-alerts [(a/mailing-save-success m)]))
    :dispatch [:route/goto :admin/mailings]}))

(rf/reg-event-db
 ::save-mailing--failure
 (fn [db [_ err]]
   (-> db
       (db/set-editing-alerts [(a/mailing-save-failed err)])
       (db/reset-saving))))

(rf/reg-event-db
 ::edit-prop-changed
 (fn [db [_ prop v]]
   (db/update-editing db assoc prop v)))

(rf/reg-event-db
 ::load-mailing
 (fn [db [_ id]]
   (->> (db/get-mailings db)
        (filter (comp (partial = id) :id))
        (first)
        (db/set-editing db))))
