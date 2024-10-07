(ns monkey.ci.gui.build.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.loader :as lo]
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

(def sorted-deps
  (comp vec sort :dependencies))

(defn sort-by-deps
  "Sorts jobs by dependencies: jobs that are dependent on another job will occur after it"
  [jobs]
  ;; Just sort dependencies and compare those.  This is not really 100% correct but it's a start.
  (sort-by sorted-deps jobs))

(rf/reg-sub
 :build/jobs
 :<- [:build/current]
 (fn [b _]
   (let [jobs (-> b :script :jobs vals)
         no-deps? (comp empty? :dependencies)]
     (loop [rem (->> jobs (remove no-deps?) (sort-by :id) vec)
            proc? #{}
            res (filterv no-deps? jobs)]
       (if (empty? rem)
         res
         (let [next-jobs (->> rem
                              (filter (comp (partial every? proc?) :dependencies)))]
           ;; Safety, should not happen
           (if (empty? next-jobs)
             (concat res rem)
             (recur (remove (set next-jobs) rem)
                    (clojure.set/union proc? (set (map :id next-jobs)))
                    (concat jobs next-jobs)))))))))

(rf/reg-sub
 :build/loading?
 (fn [db _]
   (lo/loading? db (db/get-id db))))
