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

(rf/reg-sub
 :build/jobs
 :<- [:build/current]
 (fn [b _]
   (-> b :script :jobs vals)))
