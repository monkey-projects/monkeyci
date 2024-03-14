(ns monkey.ci.gui.build.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :build/alerts db/alerts)
(u/db-sub :build/current db/build)
(u/db-sub :build/logs db/logs)
(u/db-sub :build/reloading? (comp some? db/reloading?))
(u/db-sub :build/last-reload-time db/last-reload-time)

(u/db-sub :build/log-alerts db/log-alerts)
(u/db-sub :build/downloading? (comp some? db/downloading?))
(u/db-sub :build/log-path db/log-path)

(def split-log-path #(cs/split % #"/"))

(defn- strip-prefix [l]
  (-> l
      (assoc :path (:name l))
      (update :name (comp last split-log-path))))

(defn- jobs-with-logs [b logs]
  (let [jobs (-> b :script :jobs vals)
        logs-by-id (group-by (comp first split-log-path :name) logs)]
    (letfn [(add-job-logs [{:keys [id] :as job}]
              (assoc job :logs (->> (get logs-by-id id)
                                    (map strip-prefix))))]
      (map add-job-logs jobs))))

(rf/reg-sub
 :build/jobs
 :<- [:build/current]
 :<- [:build/logs]
 (fn [[b logs] _]
   (jobs-with-logs b logs)))

(defn- add-line-breaks [s]
  (->> (cs/split-lines s)
       (interpose [:br])))

(rf/reg-sub
 :build/current-log
 (fn [db _]
   (-> (db/current-log db)
       (add-line-breaks))))

(defn global? [{n :name}]
  (not (cs/includes? n "/")))

(rf/reg-sub
 :build/global-logs
 :<- [:build/logs]
 ;; Returns all logs that are not linked to a job
 (fn [logs _]
   (filter global? logs)))
