(ns monkey.ci.gui.job.subs
  (:require [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :job/alerts db/alerts)

(rf/reg-sub
 :job/id
 :<- [:route/current]
 (fn [r _]
   (-> r (r/path-params) :job-id)))

(rf/reg-sub
 :job/current
 :<- [:job/id]
 :<- [:build/current]
 (fn [[id b] _]
   (get-in b [:script :jobs id])))
