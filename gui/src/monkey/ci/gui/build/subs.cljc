(ns monkey.ci.gui.build.subs
  (:require [monkey.ci.gui.build.db :as db]
            [re-frame.core :as rf]))

(rf/reg-sub
 :build/alerts
 (fn [db _]
   (db/alerts db)))

(rf/reg-sub
 :build/logs
 (fn [db _]
   (db/logs db)))

(rf/reg-sub
 :build/details
 :<- [:repo/builds]
 :<- [:route/current]
 (fn [[b r] _]
   (let [id (get-in r [:parameters :path :build-id])]
     (->> b
          (filter (comp (partial = id) :id))
          (first)))))

(rf/reg-sub
 :build/reloading?
 (fn [db _]
   (some? (db/reloading? db))))
