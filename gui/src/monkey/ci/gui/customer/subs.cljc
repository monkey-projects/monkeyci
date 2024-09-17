(ns monkey.ci.gui.customer.subs
  (:require [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :customer/repo-alerts db/repo-alerts)
(u/db-sub ::github-repos db/github-repos)
(u/db-sub :customer/create-alerts db/create-alerts)
(u/db-sub :customer/creating? db/customer-creating?)

(rf/reg-sub
 :customer/info
 :<- [:loader/value db/customer]
 identity)

(rf/reg-sub
 :customer/loading?
 :<- [:loader/loading? db/customer]
 identity)

(rf/reg-sub
 :customer/alerts
 (fn [db _]
   (lo/get-alerts db db/customer)))

(rf/reg-sub
 :customer/repos
 :<- [:customer/info]
 (fn [ci _]
   (:repos ci)))

(rf/reg-sub
 :customer/github-repos
 :<- [:customer/repos]
 :<- [::github-repos]
 (fn [[cr gr] _]
   (letfn [(watched-repo [r]
             (->> cr
                  (filter (some-fn (comp (partial = (:id r)) :github-id)
                                   (comp (partial = (:ssh-url r)) :url)
                                   (comp (partial = (:clone-url r)) :url)))
                  first))]
     (map (fn [r]
            (let [w (watched-repo r)]
              (assoc r
                     :monkeyci/watched? (some? w)
                     :monkeyci/repo w)))
          gr))))

(rf/reg-sub
 :customer/recent-builds
 :<- [:loader/value db/customer]
 :<- [:loader/value db/recent-builds]
 (fn [[cust rb] _]
   (let [repos (->> cust
                    :repos
                    (group-by :id))
         add-repo (fn [{:keys [repo-id] :as b}]
                    (assoc b :repo (-> repos (get repo-id) first)))]
     (->> rb
          (sort-by :start-time)
          (reverse)
          (map add-repo)))))

(rf/reg-sub
 :customer/stats
 :<- [:loader/value db/stats]
 identity)
