(ns monkey.ci.gui.build.subs
  (:require [clojure.string :as cs]
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
   (let [jobs (-> b :script :jobs vals)]
     (loop [rem (->> jobs (sort-by :id) vec)
            proc? #{}
            res []]
       (if (empty? rem)
         res
         (let [next-jobs (->> rem
                              (filter (comp (partial every? proc?) :dependencies)))]
           (if (empty? next-jobs)
             ;; Safety, should not happen
             (concat res rem)
             (recur (remove (set next-jobs) rem)
                    (clojure.set/union proc? (set (map :id next-jobs)))
                    (concat res next-jobs)))))))))

(rf/reg-sub
 :build/loading?
 (fn [db _]
   (lo/loading? db (db/get-id db))))
