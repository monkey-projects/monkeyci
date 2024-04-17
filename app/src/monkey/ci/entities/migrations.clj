(ns monkey.ci.entities.migrations
  (:require [clojure.tools.logging :as log]
            [ragtime
             [core :as rt]
             [next-jdbc :as rj]]))

(defn- load-migrations [conn]
  (let [db (rj/sql-database conn)
        mig (rj/load-resources "migrations")
        idx (rt/into-index mig)]
    [db mig idx]))

(defn run-migrations!
  "Runs SQL migrations on the database indicated by the connection"
  [conn]
  (let [[db mig idx] (load-migrations conn)]
    (log/info "Applying" (count mig) "migrations")
    (rt/migrate-all db idx mig)))

(defn with-migrations
  "Runs migrations, executes `f` and then rolls back.  Useful for testing."
  [conn f]
  (let [[db mig idx] (load-migrations conn)]
    (log/info "Applying" (count mig) "migrations")
    (rt/migrate-all db idx mig)
    (try
      (f)
      (finally
        (rt/rollback-last db idx (count mig))))))
