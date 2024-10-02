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
   (->> (db/get-logs db path)
        :data
        :result
        first
        convert-result)))

(defn- error-count [{:keys [errors failures]}]
  (+ (count errors) (count failures)))

(rf/reg-sub
 :job/test-cases
 :<- [:job/current]
 (fn [job _]
   (->> (get-in job [:result :monkey.ci/tests])
        (mapcat :test-cases)
        (sort-by error-count)
        (reverse))))

(def log-path-regex #"^.*([0-9]+)_(out|err).log$")

(defn- path->line [path]
  (when-let [[_ idx :as p] (re-matches log-path-regex path)]
    (u/parse-int idx)))

(defn- path->type [path]
  (keyword (nth (re-matches log-path-regex path) 2)))

(rf/reg-sub
 :job/script-with-logs
 :<- [:job/current]
 :<- [:job/log-files]
 (fn [[job files] _]
   (let [file-per-line (group-by path->line files)]
     (letfn [(as-types-map [paths]
               (->> paths
                    (map (fn [l]
                           [(path->type l) l]))
                    (into {})))
             (->out [idx line]
               (let [m (get file-per-line idx)]
                 ;; TODO Also add expanded status
                 (cond-> {:cmd line}
                   m (merge (as-types-map m)))))]
       (->> (:script job)
            (map-indexed ->out))))))
