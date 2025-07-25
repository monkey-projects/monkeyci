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
    {:init-events [[:webhooks/load]]
     :leave-event [:webhooks/leave]})))

(rf/reg-event-db
 :webhooks/leave
 (fn [db _]
   (-> db
       (lo/reset-initialized db/id)
       (db/set-webhooks nil))))

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
    :db (-> db
            (db/set-creating)
            (db/reset-alerts))}))

(rf/reg-event-db
 :webhooks/new--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/set-new body)
       (db/update-webhooks conj body)
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

(rf/reg-event-db
 :webhooks/delete-confirm
 (fn [db [_ id]]
   (db/set-delete-curr db id)))

(rf/reg-event-fx
 :webhooks/delete
 (fn [{:keys [db]} _]
   (let [id (db/get-delete-curr db)]
     {:dispatch [:secure-request
                 :delete-webhook
                 {:webhook-id id}
                 [:webhooks/delete--success id]
                 [:webhooks/delete--failed id]]
      :db (-> db
              (db/set-deleting id)
              (db/reset-alerts))})))

(rf/reg-event-db
 :webhooks/delete--success
 (fn [db [_ id]]
   (letfn [(remove-wh [wh]
             (remove (fn [w]
                       (= (:id w) id))
                     wh))]
     (-> db
         (db/reset-deleting id)
         (db/set-alerts [(a/webhook-delete-success id)])
         (db/update-webhooks remove-wh)))))

(rf/reg-event-db
 :webhooks/delete--failed
 (fn [db [_ id err]]
   (-> db
       (lo/on-failure db/id a/webhooks-delete-failed err)
       (db/reset-deleting id))))
