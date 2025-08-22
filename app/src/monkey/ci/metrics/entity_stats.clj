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
                {:callback #(st/count-users st)}))

(defn org-count-gauge
  "Creates a gauge that returns number of organizations in db"
  [st reg]
  (p/make-gauge (c/metric-id ["org" "count"]) reg
                {:callback #(st/count-orgs st)}))
