(ns monkey.ci.gui.home.subs
  (:require [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :user/customers db/customers)
(u/db-sub :user/alerts db/alerts)
(u/db-sub :customer/join-alerts db/join-alerts)
(u/db-sub :customer/searching? (comp true? db/customer-searching?))
(u/db-sub :customer/search-results db/search-results)
(u/db-sub :user/join-requests db/join-requests)

(rf/reg-sub
 :customer/join-list
 :<- [:customer/search-results]
 :<- [:login/user]
 (fn [[r u] _]
   (when r
     (let [cust (set (:customers u))
           mark-joined (fn [{:keys [id] :as c}]
                         (cond-> c
                           (cust id) (assoc :joined? true)))]
       (map mark-joined r)))))

(rf/reg-sub
 :customer/joining?
 (fn [db [_ cust-id]]
   (db/customer-joining? db cust-id)))
