(ns monkey.ci.gui.webhooks.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.webhooks.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :webhooks/init
 (fn [{:keys [db]} _]
   (lo/on-initialize
    db db/id
    {:init-events [[:webhooks/load]]})))

(rf/reg-event-fx
 :webhooks/load
 (lo/loader-evt-handler
  db/id
  (fn [_ {:keys [db]} _]
    (let [params (get-in db [:route/current :parameters :path])]
      [:secure-request
       :get-repo-webhooks
       (select-keys params [:org-id :repo-id])
       [:webhooks/load--success]
       [:webhooks/load--failed]]))))

(rf/reg-event-db
 :webhooks/load--success
 (fn [db [_ resp]]
   (lo/on-success db db/id resp)))

(rf/reg-event-db
 :webhooks/load--failed
 (fn [db [_ err]]
   (lo/on-failure db db/id a/webhooks-load-failed err)))

(rf/reg-event-fx
 :webhooks/new
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :create-webhook
               {:webhook (-> (get-in db [:route/current :parameters :path])
                             (select-keys [:org-id :repo-id]))}
               [:webhooks/new--success]
               [:webhooks/new--failed]]
    :db (db/set-creating db)}))

(rf/reg-event-db
 :webhooks/new--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/set-new body)
       (db/reset-creating))))

(rf/reg-event-db
 :webhooks/new--failed
 (fn [db [_ err]]
   (-> db
       (lo/on-failure db/id a/webhooks-new-failed err)
       (db/reset-creating))))

(rf/reg-event-db
 :webhooks/close-new
 (fn [db _]
   (db/reset-new db)))
