(ns monkey.ci.gui.build.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.build.db :as db]
            [re-frame.core :as rf]))

(rf/reg-sub
 :build/alerts
 (fn [db _]
   (db/alerts db)))

(rf/reg-sub
 :build/logs
 (fn [db _]
   (db/logs db)))

(def split-log-path #(cs/split % #"/"))

(rf/reg-sub
 :build/details
 :<- [:repo/builds]
 :<- [:route/current]
 :<- [:build/logs]
 (fn [[b r l] _]
   (let [id (get-in r [:parameters :path :build-id])
         logs-by-id (group-by (comp vec (partial take 2) split-log-path :name) l)
         strip-prefix (fn [l]
                        (-> l
                            (assoc :path (:name l))
                            (update :name (comp last split-log-path))))
         add-step-logs (fn [pn s]
                         (assoc s :logs (->> (get logs-by-id [pn (str (:index s))])
                                             (map strip-prefix))))
         add-logs (fn [p]
                    (update p :steps (partial map (partial add-step-logs (:name p)))))]
     (some-> (filter (comp (partial = id) :id) b)
             (first)
             (update :pipelines (partial map add-logs))))))

(rf/reg-sub
 :build/reloading?
 (fn [db _]
   (some? (db/reloading? db))))

(rf/reg-sub
 :build/current-log
 (fn [db _]
   ;; TODO
   {}))
