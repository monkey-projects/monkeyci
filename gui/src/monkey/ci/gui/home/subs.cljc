(ns monkey.ci.gui.home.subs
  (:require [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :user/customers db/get-customers)
(u/db-sub :user/alerts db/get-alerts)
(u/db-sub :customer/join-alerts db/join-alerts)
(u/db-sub :customer/searching? (comp true? db/customer-searching?))
(u/db-sub :customer/search-results db/search-results)
(u/db-sub :user/join-requests db/join-requests)

(rf/reg-sub
 :customer/joining?
 (fn [db [_ cust-id]]
   (if cust-id
     (db/customer-joining? db cust-id)
     (or (db/customer-joining? db) #{}))))

(rf/reg-sub
 :customer/join-list
 :<- [:customer/search-results]
 :<- [:login/user]
 :<- [:user/join-requests]
 :<- [:customer/joining?]
 (fn [[r u jr j?] _]
   (when r
     (let [cust (set (:customers u))
           mark-joined (fn [{:keys [id] :as c}]
                         (cond-> c
                           (cust id) (assoc :status :joined)
                           (j? id) (assoc :status :joining)))]
       (map mark-joined r)))))
