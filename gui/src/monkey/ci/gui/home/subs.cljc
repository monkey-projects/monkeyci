(ns monkey.ci.gui.home.subs
  (:require [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :user/orgs db/get-orgs)
(u/db-sub :user/alerts db/get-alerts)
(u/db-sub :org/join-alerts db/join-alerts)
(u/db-sub :org/searching? (comp true? db/org-searching?))
(u/db-sub :org/search-results db/search-results)
(u/db-sub :user/join-requests db/join-requests)

(rf/reg-sub
 :org/joining?
 (fn [db [_ cust-id]]
   (if cust-id
     (db/org-joining? db cust-id)
     (or (db/org-joining? db) #{}))))

(rf/reg-sub
 :org/join-list
 :<- [:org/search-results]
 :<- [:login/user]
 :<- [:user/join-requests]
 :<- [:org/joining?]
 (fn [[r u jr j?] _]
   (when r
     (let [cust (set (:orgs u))
           mark-joined (fn [{:keys [id] :as c}]
                         (cond-> c
                           (cust id) (assoc :status :joined)
                           (j? id) (assoc :status :joining)))]
       (map mark-joined r)))))
