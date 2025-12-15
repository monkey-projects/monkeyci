(ns monkey.ci.gui.billing.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.billing.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::load-invoicing
 (lo/loader-evt-handler
  db/billing-id
  (fn [_ {:keys [db]} _]
    [:secure-request
     :get-org-invoicing-settings
     {:org-id (r/org-id db)}
     [::load-invoicing--success]
     [::load-invoicing--failure]])))

(rf/reg-event-db
 ::load-invoicing--success
 (fn [db [_ resp]]
   (lo/on-success db db/billing-id resp)))

(rf/reg-event-db
 ::load-invoicing--failure
 (fn [db [_ err]]
   (lo/on-failure db db/billing-id a/invoice-settings-load-failed err)))

(rf/reg-event-db
 ::invoicing-settings-changed
 (fn [db [_ prop v]]
   (db/update-invoicing-settings db assoc prop v)))

(rf/reg-event-db
 ::invoicing-address-changed
 (fn [db [_ idx v]]
   (db/update-invoicing-settings db update :address (comp vec (partial mc/replace-nth idx v))))) 

(rf/reg-event-fx
 ::save-invoicing
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :update-org-invoicing-settings
               {:org-id (r/org-id db)
                :settings (db/get-invoicing-settings db)}
               [::save-invoicing--success]
               [::save-invoicing--failure]]
    :db (db/reset-billing-alerts db)}))

(rf/reg-event-db
 ::save-invoicing--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/set-invoicing-settings body)
       (db/set-billing-alerts [(a/invoice-settings-save-success)]))))

(rf/reg-event-db
 ::save-invoicing--failure
 (fn [db [_ err]]
   (-> db
       (db/set-billing-alerts [(a/invoice-settings-save-failed err)]))))
