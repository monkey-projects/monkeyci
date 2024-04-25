(ns monkey.ci.gui.loki
  "Functions for fetching loki logs"
  (:require #?@(:node []
                :cljs [[ajax.core :as ajax]])
            [clojure.string :as cs]))

(def loki-url
  #?(:cljs (if (exists? js/logUrl)
             js/logUrl
             "http://test")
     :clj "http://test"))

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

(defn job-request
  "Creates a request map for the job.  It can server as a template for specific
   requests, but it will contain a query, start time and end time param (if the
   job has finished)."
  ([sid {:keys [id start-time end-time]}]
   {:method :get
    :response-format #?@(:node [:json]
                         :cljs [(ajax/json-response-format {:keywords? true})])
    :params (cond-> {:query (query->str (job-query sid id))
                     :start (int (/ start-time 1000))
                     :direction "forward"}
              ;; Add one second to end time
              end-time (assoc :end (inc (int (/ end-time 1000)))))
    :headers {"X-Scope-OrgID" (first sid)}})
  ([path sid job]
   (-> (job-request sid job)
       (assoc :uri (str loki-url path)))))

(defn with-query
  "Sets given query on the job request"
  [r q]
  (assoc-in r [:params :query] q))

(def query-range-request (partial job-request "/query_range"))
(def index-stats-request (partial job-request "/index/stats"))

(defn label-values-request [lbl sid job]
  (job-request (str "/label/" lbl "/values") sid job))

(def filename-values-request (partial label-values-request "filename"))
