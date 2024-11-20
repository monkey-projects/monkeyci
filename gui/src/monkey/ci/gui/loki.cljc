(ns monkey.ci.gui.loki
  "Functions for fetching loki logs"
  (:require [clojure.string :as cs]))

(defn query->str [q]
  (letfn [(entry [[k v]]
            (str k "=\"" v "\""))]
    (str "{"
         (->> q
              (map entry)
              (cs/join ","))
         "}")))

(defn job-query
  "Creates a query object for the given build sid and job"
  [[_ repo-id build-id] job-id]
  {"repo_id" repo-id
   "build_id" build-id
   "job_id" job-id})

(defn request-params [sid {:keys [id start-time end-time]}]
  (cond-> {:query (query->str (job-query sid id))
           :start (int (/ start-time 1000))
           :direction "forward"}
    ;; Add one second to end time
    end-time (assoc :end (inc (int (/ end-time 1000))))))

(defn with-query
  "Sets given query on the job request"
  [r q]
  (assoc-in r [:params :query] q))
