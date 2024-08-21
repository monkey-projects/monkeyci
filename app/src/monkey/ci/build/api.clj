(ns monkey.ci.build.api
  "Functions for invoking the build script API."
  (:require [clojure.tools.logging :as log]
            [monkey.ci.build :as b]))

(def rt->api-client (comp :client :api))

(defn- repo-path [rt]
  (apply format "/customer/%s/repo/%s" (b/get-sid rt)))

(defn- fetch-params* [rt]
  (let [client (rt->api-client rt)
        [cust-id repo-id] (b/get-sid rt)]
    (log/debug "Fetching repo params for" cust-id repo-id)
    (->> @(client {:url (str (repo-path rt) "/param")
                   :method :get})
         (map (juxt :name :value))
         (into {}))))

;; Use memoize because we'll only want to fetch them once
(def build-params (memoize fetch-params*))

(defn download-artifact
  "Downloads the artifact with given id for the current job.  Returns an input
   stream that can then be written to disk, or unzipped using archive functions."
  [rt id]
  (let [client (rt->api-client rt)
        sid (b/get-sid rt)]
    (log/debug "Downloading artifact for build" sid ":" id)
    @(client {:url (format (str (repo-path rt) "/builds/%s/artifact/%s/download") (last sid) id)
              :method :get})))
