(ns monkey.ci.gui.repo.subs
  (:require [monkey.ci.gui.org.subs]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 :repo/info
 :<- [:org/info]
 (fn [c [_ repo-id]]
   (println "repo id:" repo-id)
   ;; TODO Optimize org structure for faster lookup
   (some->> c
            :repos
            (u/find-by-id repo-id))))

(rf/reg-sub
 :repo/builds
 (fn [db _]
   (let [params (get-in db [:route/current :parameters :path])
         parse-time (fn [b]
                      (update b :start-time (comp str t/parse)))]
     (some->> (db/get-builds db)
              (map parse-time)
              (sort-by :start-time)
              (reverse)
              (map (partial merge params))))))

(u/db-sub :repo/alerts db/alerts)
(u/db-sub :repo/latest-build db/latest-build)
(u/db-sub :repo/show-trigger-form? db/show-trigger-form?)
(u/db-sub :repo/edit-alerts db/edit-alerts)
(u/db-sub :repo/editing db/editing)
(u/db-sub :repo/saving? (comp true? db/saving?))
(u/db-sub :repo/deleting? (comp true? db/deleting?))

(rf/reg-sub
 :builds/init-loading?
 :<- [:loader/init-loading? db/id]
 (fn [l _]
   l))

(rf/reg-sub
 :builds/loaded?
 :<- [:loader/loaded? db/id]
 (fn [l _]
   l))
