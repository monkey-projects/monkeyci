(ns monkey.ci.gui.build.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :build/alerts db/alerts)
(u/db-sub :build/current db/build)
(u/db-sub :build/logs db/logs)
(u/db-sub :build/reloading? (comp some? db/reloading?))
(u/db-sub :build/auto-reload? db/auto-reload?)

(u/db-sub :build/log-alerts db/log-alerts)
(u/db-sub :build/downloading? (comp some? db/downloading?))
(u/db-sub :build/log-path db/log-path)

(def split-log-path #(cs/split % #"/"))

(rf/reg-sub
 :build/details
 :<- [:build/current]
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
     (some-> b
             (update :pipelines (partial map add-logs))))))

(defn- add-line-breaks [s]
  (->> (cs/split-lines s)
       (interpose [:br])))

(rf/reg-sub
 :build/current-log
 (fn [db _]
   (-> (db/current-log db)
       (add-line-breaks))))
