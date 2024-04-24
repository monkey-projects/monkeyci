(ns monkey.ci.gui.loki
  "Functions for fetching loki logs"
  (:require #?@(:node []
                :cljs [[ajax.core :as ajax]])))

(def loki-url
  #?(:cljs (if (exists? js/logUrl)
             js/logUrl
             "http://test")
     :clj "http://test"))

(defn job-query
  "Creates a query string for the given build sid and job"
  [[_ repo-id build-id] job-id]
  (str "{repo_id=\"" repo-id
       "\",build_id=\"" build-id
       "\",job_id=\"" job-id "\"}"))

(defn job-logs-request
  "Given a job, creates an http-xhrio request map that can be used to fetch
   the logs for that job.  Security and handlers still need to be added."
  [sid {:keys [id start-time end-time]}]
  {:uri (str loki-url "/query_range")
   :method :get
   :response-format #?@(:node [:json]
                        :cljs [(ajax/json-response-format {:keywords? true})])
   :params {:query (job-query sid id)
            :start (int (/ start-time 1000))
            ;; Add one second to end time
            :end (inc (int (/ end-time 1000)))
            :direction "forward"}})

(defn fetch-logs [])
