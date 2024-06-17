(ns monkey.ci.gui.job.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :job/alerts db/global-alerts)
(u/db-sub :job/log-files db/log-files)

(rf/reg-sub
 :job/path-alerts
 (fn [db [_ path]]
   (db/path-alerts db path)))

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

(defn- convert-result [{:keys [stream values]}]
  ;; TODO Add timestamp (first entry in each value)
  (->> (map second values)
       (interpose [:br])))

(rf/reg-sub
 :job/logs
 (fn [db [_ path]]
   (->> (db/logs db path)
        :data
        :result
        first
        convert-result)))

(rf/reg-sub
 :job/test-cases
 :<- [:job/current]
 (fn [job _]
   (->> (get-in job [:result :monkey.ci/tests])
        (mapcat :test-cases))))
