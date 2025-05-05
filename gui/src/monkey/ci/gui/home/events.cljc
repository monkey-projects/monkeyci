(ns monkey.ci.gui.home.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :home/initialize
 (fn [{:keys [db]} _]
   (lo/on-initialize
    db
    db/id
    {:init-events [[:user/load-orgs]]})))

(rf/reg-event-fx
 :user/load-orgs
 (lo/loader-evt-handler
  db/id
  (fn [id {:keys [db]} _]
    [:secure-request
     :get-user-orgs
     {:user-id (:id (ldb/user db))}
     [:user/load-orgs--success]
     [:user/load-orgs--failed]])))

(rf/reg-event-db
 :user/load-orgs--success
 (fn [db [_ resp]]
   (lo/on-success db db/id resp)))

(rf/reg-event-db
 :user/load-orgs--failed
 (fn [db [_ err]]
   (lo/on-failure db db/id a/user-load-orgs-failed err)))

(rf/reg-event-db
 :org/join-init
 (fn [db _]
   (-> db
       (db/set-search-results nil)
       (db/set-join-requests nil)
       (db/clear-org-joining))))

(rf/reg-event-fx
 :org/search
 (fn [{:keys [db]} [_ {:keys [org-search]}]]
   {:dispatch [:secure-request
               :search-orgs
               {:name (first org-search)}
               [:org/search--success]
               [:org/search--failed]]
    :db (-> db
            (db/set-org-searching true)
            (db/clear-join-alerts))}))

(rf/reg-event-db
 :org/search--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/reset-org-searching)
       (db/set-search-results body))))

(rf/reg-event-db
 :org/search--failed
 (fn [db [_ err]]
   (-> db
       (db/reset-org-searching)
       (db/set-join-alerts [{:type :danger
                             :message (str "Failed to search for orgs: " (u/error-msg err))}]))))

(rf/reg-event-fx
 :join-request/load
 (fn [{:keys [db]} _]
   (let [user (ldb/user db)]
     {:dispatch [:secure-request
                 :get-user-join-requests
                 {:user-id (:id user)}
                 [:join-request/load--success]
                 [:join-request/load--failed]]})))

(rf/reg-event-db
 :join-request/load--success
 (fn [db [_ {:keys [body]}]]
   (db/set-join-requests db body)))

(rf/reg-event-db
 :join-request/load--failed
 (fn [db [_ err]]
   (-> db
       (db/set-join-requests [])
       (db/set-join-alerts [{:type :danger
                             :message (str "Failed to retrieve requests: " (u/error-msg err))}]))))

(rf/reg-event-fx
 :org/join
 (fn [{:keys [db]} [_ cust-id]]
   (let [user (ldb/user db)]
     {:dispatch [:secure-request
                 :create-user-join-request
                 {:join-request
                  {:org-id cust-id}
                  :user-id (:id user)}
                 [:org/join--success]
                 [:org/join--failed cust-id]]
      :db (db/mark-org-joining db cust-id)})))

(rf/reg-event-db
 :org/join--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/unmark-org-joining (:org-id body))
       (db/update-join-requests (fnil conj []) body))))

(rf/reg-event-db
 :org/join--failed
 (fn [db [_ cust-id err]]
   (-> db
       (db/unmark-org-joining cust-id)
       (db/set-join-alerts [{:type :danger
                             :message (str "Failed to send join request: " (u/error-msg err))}]))))
