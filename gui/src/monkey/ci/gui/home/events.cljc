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
