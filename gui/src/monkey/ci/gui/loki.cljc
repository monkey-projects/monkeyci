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

(defn- millis->nanos [ms]
  (* ms 1000000))

(defn request-params [sid {:keys [id start-time end-time]}]
  ;; Timestamp is required otherwise loki may return empty results
  ;; when query cache is cleared.  We take the time period extra large
  ;; to ensure logs that have been committed later are also found.
  (cond-> {:query (query->str (job-query sid id))
           :start (millis->nanos (- start-time 60000))
           :direction "forward"}
    end-time (assoc :end (millis->nanos (+ end-time 60000)))))

(defn with-query
  "Sets given query on the job request"
  [r q]
  (assoc-in r [:params :query] q))
