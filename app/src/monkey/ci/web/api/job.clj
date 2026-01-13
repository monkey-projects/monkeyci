(ns monkey.ci.web.api.job
  "Job-related api handlers"
  (:require [monkey.ci.events.builders :as eb]
            [monkey.ci.storage :as st]
            [monkey.ci.web
             [common :as wc]
             [response :as wr]]
            [ring.util.response :as rur]))

(def req->job-sid (comp (juxt :org-id :repo-id :build-id :job-id) :path :parameters))

(defn- with-job [req f]
  (if-let [job (st/find-job (wc/req->storage req) (req->job-sid req))]
    (f job)
    (rur/not-found nil)))

(defn get-job [req]
  (with-job req rur/response))

(defn unblock-job [req]
  (with-job req
    (fn [_]
      (let [sid (req->job-sid req)]
        (-> (rur/status 202)
            (wr/add-event (eb/job-unblocked-evt (last sid) (drop-last sid))))))))
