(ns monkey.ci.gui.build.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :build/alerts db/alerts)
(u/db-sub :build/current db/build)
(u/db-sub :build/reloading? (comp some? db/reloading?))
(u/db-sub :build/expanded-jobs db/expanded-jobs)

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
   (-> b :script :jobs vals sort-by-deps)))
