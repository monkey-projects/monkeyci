(ns monkey.ci.gui.home.events
  (:require [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :user/load-customers
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :get-user-customers
               {:user-id (:id (ldb/user db))}
               [:user/load-customers--success]
               [:user/load-customers--failed]]
    :db (db/set-alerts db
                       [{:type :info
                         :message "Retrieving linked customers..."}])}))

(rf/reg-event-db
 :user/load-customers--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/clear-alerts)
       (db/set-customers body))))

(rf/reg-event-db
 :user/load-customers--failed
 (fn [db [_ err]]
   (db/set-alerts db [{:type :danger
                       :message (str "Could not retrieve linked customers: " (u/error-msg err))}])))

(rf/reg-event-db
 :customer/join-init
 (fn [db _]
   (-> db
       (db/set-search-results nil)
       (db/set-join-requests nil)
       (db/clear-customer-joining))))

(rf/reg-event-fx
 :customer/search
 (fn [{:keys [db]} [_ {:keys [customer-search]}]]
   {:dispatch [:secure-request
               :search-customers
               {:name (first customer-search)}
               [:customer/search--success]
               [:customer/search--failed]]
    :db (-> db
            (db/set-customer-searching true)
            (db/clear-join-alerts))}))

(rf/reg-event-db
 :customer/search--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/reset-customer-searching)
       (db/set-search-results body))))

(rf/reg-event-db
 :customer/search--failed
 (fn [db [_ err]]
   (-> db
       (db/reset-customer-searching)
       (db/set-join-alerts [{:type :danger
                             :message (str "Failed to search for customers: " (u/error-msg err))}]))))

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
 :customer/join
 (fn [{:keys [db]} [_ cust-id]]
   (let [user (ldb/user db)]
     {:dispatch [:secure-request
                 :create-user-join-request
                 {:join-request
                  {:customer-id cust-id}
                  :user-id (:id user)}
                 [:customer/join--success]
                 [:customer/join--failed cust-id]]
      :db (db/mark-customer-joining db cust-id)})))

(rf/reg-event-db
 :customer/join--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/unmark-customer-joining (:customer-id body))
       (db/update-join-requests (fnil conj []) body))))

(rf/reg-event-db
 :customer/join--failed
 (fn [db [_ cust-id err]]
   (-> db
       (db/unmark-customer-joining cust-id)
       (db/set-join-alerts [{:type :danger
                             :message (str "Failed to send join request: " (u/error-msg err))}]))))
