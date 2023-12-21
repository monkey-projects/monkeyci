(ns monkey.ci.gui.repo.subs
  (:require [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 :repo/info
 :<- [:customer/info]
 (fn [c [_ proj-id repo-id]]
   ;; TODO Optimize customer structure for faster lookup
   (some->> (:projects c)
            (u/find-by-id proj-id)
            :repos
            (u/find-by-id repo-id))))

(rf/reg-sub
 :repo/alerts
 (fn [db _]
   (db/alerts db)))

(rf/reg-sub
 :repo/builds
 (fn [db _]
   (db/builds db)))
