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
(u/db-sub :build/last-reload-time db/last-reload-time)

(u/db-sub :build/log-alerts db/log-alerts)
(u/db-sub :build/downloading? (comp some? db/downloading?))
(u/db-sub :build/log-path db/log-path)

(def split-log-path #(cs/split % #"/"))

(defn- strip-prefix [l]
  (-> l
      (assoc :path (:name l))
      (update :name (comp last split-log-path))))

(defn- pipelines-as-jobs [pl logs]
  (let [logs-by-id (group-by (comp vec (partial take 2) split-log-path :name) logs)]
    (letfn [(add-step-logs [s pn]
              (assoc s :logs (->> (get logs-by-id [pn (str (:index s))])
                                  (map strip-prefix))))
            
            (steps->jobs [p]
              (map (fn [s]
                     ;; TODO Dependencies
                     (-> s
                         (assoc :id (str (:name p) "-" (:index s))
                                :labels {"pipeline" (:name p)})
                         (add-step-logs (:name p))))
                   ((some-fn :steps :jobs) p)))]
      (mapcat steps->jobs pl))))

(defn- jobs-with-logs [{:keys [jobs]} logs]
  (let [logs-by-id (group-by (comp first split-log-path :name) logs)]
    (letfn [(add-job-logs [{:keys [id] :as job}]
              (assoc job :logs (->> (get logs-by-id id)
                                    (map strip-prefix))))]
      (map add-job-logs jobs))))

(rf/reg-sub
 :build/jobs
 :<- [:build/current]
 :<- [:build/logs]
 (fn [[b logs] _]
   (let [p (:pipelines b)]
     (if (not-empty p)
       (pipelines-as-jobs p logs)
       (jobs-with-logs b logs)))))

(defn- add-line-breaks [s]
  (->> (cs/split-lines s)
       (interpose [:br])))

(rf/reg-sub
 :build/current-log
 (fn [db _]
   (-> (db/current-log db)
       (add-line-breaks))))
