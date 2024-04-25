(ns monkey.ci.gui.job.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.job.db :as db]
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

(defn- convert-result [{:keys [stream values]}]
  (let [fn (some-> (:filename stream)
                   (cs/split #"/")
                   last)]
    {:file fn
     ;; TODO Add timestamp (first entry in each value)
     :contents (->> (map second values)
                    (interpose [:br]))}))

(rf/reg-sub
 :job/logs
 (fn [db _]
   (->> (db/logs db)
        :data
        :result
        (map convert-result))))
