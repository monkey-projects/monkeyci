(ns monkey.ci.storage.sql
  "Storage implementation that uses SQL (e.g. MySQL or HSQLDB"
  (:require [clojure.tools.logging :as log]
            [honey.sql :as h]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [next.jdbc :as jdbc]
            [next.jdbc
             [result-set :as rs]
             [sql :as sql]]
            [ragtime.core :as rt]
            [ragtime.next-jdbc :as rj]))

(defn sid->table [sid]
  (if (and (= st/global (first sid))
           (= "customers" (second sid)))
    :customers))

(def sid->id last)

(defn- format-sql [q]
  (h/format q))

(defn- id->uuid [x]
  (-> x
      (assoc :uuid (:id x))
      (dissoc :id)))

(defn- uuid->id [x]
  (-> x
      (assoc :id (:uuid x))
      (dissoc :uuid)))

(def default-opts {:builder-fn rs/as-unqualified-lower-maps})

(defn select-by-uuid [ds t id]
  (some->> (jdbc/execute! ds
                          (format-sql {:select :*
                                       :from [t]
                                       :where [:= :uuid id]})
                          default-opts)
           (first)))

(deftype SqlStorage [ds]
  p/Storage
  (write-obj [_ sid obj]
    (when-let [t (sid->table sid)]
      (let [e (select-by-uuid ds t (sid->id sid))
            r (id->uuid obj)]
        (if e
          (sql/update! ds t r {:id (:id e)})
          (sql/insert! ds t r))
        sid)))

  (read-obj [_ sid]
    (when-let [t (sid->table sid)]
      (some->> (select-by-uuid ds t (sid->id sid))
               (uuid->id))))

  (list-obj [_ sid]
    (when-let [t (sid->table sid)]
      (jdbc/execute! ds
                     (format-sql {:select :*
                                  :from [t]})
                     default-opts))))

(defn run-migrations!
  "Runs SQL migrations on the database indicated by the connection"
  [conn]
  (let [db (rj/sql-database conn)
        mig (rj/load-resources "migrations")
        idx (rt/into-index mig)]
    (log/info "Applying" (count mig) "migrations")
    (rt/migrate-all db idx mig)))
