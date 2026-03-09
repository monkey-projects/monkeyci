(ns monkey.ci.gui.build.subs
  (:require [clojure.string :as cs]
            [monkey.ci.common.jobs :as cj]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :build/alerts db/get-alerts)
(u/db-sub :build/current db/get-build)
(u/db-sub :build/canceling? db/canceling?)
(u/db-sub :build/retrying? db/retrying?)

(def split-log-path #(cs/split % #"/"))

(defn- strip-prefix [l]
  (-> l
      (assoc :path (:name l))
      (update :name (comp last split-log-path))))

(rf/reg-sub
 :build/jobs
 :<- [:build/current]
 (fn [b _]
   ;; Sort the jobs in the build by dependency order
   (-> b :script :jobs vals
       (cj/sort-by-deps))))

(rf/reg-sub
 :build/loading?
 (fn [db _]
   (lo/loading? db (db/get-id db))))
