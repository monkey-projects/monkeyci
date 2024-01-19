(ns monkey.ci.gui.repo.subs
  (:require [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 :repo/info
 :<- [:customer/info]
 (fn [c [_ repo-id]]
   ;; TODO Optimize customer structure for faster lookup
   (some->> c
            :repos
            (u/find-by-id repo-id))))

(rf/reg-sub
 :repo/alerts
 (fn [db _]
   (db/alerts db)))

(rf/reg-sub
 :repo/builds
 (fn [db _]
   (let [params (get-in db [:route/current :parameters :path])]
     (some->> (db/builds db)
              (sort-by :time)
              (reverse)
              (map #(assoc % :build-id (:id %)))
              (map (partial merge params))))))

(rf/reg-sub
 :repo/latest-build
 (fn [db _]
   (db/latest-build db)))
