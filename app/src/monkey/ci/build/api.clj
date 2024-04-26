(ns monkey.ci.build.api
  "Functions for invoking the script API."
  (:require [clojure.tools.logging :as log]
            [martian.core :as martian]
            [monkey.ci.build :as b]))

(def rt->api-client (comp :client :api))

(defn- fetch-params* [rt]
  (let [client (rt->api-client rt)
        [cust-id repo-id] (b/get-sid rt)]
    (log/debug "Fetching repo params for" cust-id repo-id)
    (->> @(client {:url (format "/customer/%s/repo/%s/param" cust-id repo-id)
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
    @(client {:url (apply format "/customer/%s/repo/%s/build/%s/artifact/%s/download" (conj sid id))
              :method :get})))
