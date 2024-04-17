(ns monkey.ci.entities.migrations
  (:require [clojure.tools.logging :as log]
            [ragtime
             [core :as rt]
             [next-jdbc :as rj]]))

(defn run-migrations!
  "Runs SQL migrations on the database indicated by the connection"
  [conn]
  (let [db (rj/sql-database conn)
        mig (rj/load-resources "migrations")
        idx (rt/into-index mig)]
    (log/info "Applying" (count mig) "migrations")
    (rt/migrate-all db idx mig)))
