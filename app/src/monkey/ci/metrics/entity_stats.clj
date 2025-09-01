(ns monkey.ci.metrics.entity-stats
  "Metrics that reflect some statistics on entities.  These are typically
   lazy and execute a count query on retrieval."
  (:require [monkey.ci.metrics
             [common :as c]
             [prometheus :as p]]
            [monkey.ci.storage :as st]))

(defn user-count-gauge
  "Creates a gauge that returns number of users in db"
  [st reg]
  (p/make-gauge (c/metric-id ["user" "count"]) reg
                {:callback #(st/count-users st)
                 :description "Total number of users in the database"}))

(defn org-count-gauge
  "Creates a gauge that returns number of organizations in db"
  [st reg]
  (p/make-gauge (c/metric-id ["org" "count"]) reg
                {:callback #(st/count-orgs st)
                 :description "Total number of organizations in the database"}))

(defn repo-count-gauge
  "Creates a gauge that returns number of repos in db"
  [st reg]
  (p/make-gauge (c/metric-id ["repo" "count"]) reg
                {:callback #(st/count-repos st)
                 :description "Total number of repositories in the database"}))
