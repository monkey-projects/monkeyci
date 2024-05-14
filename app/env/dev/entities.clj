(ns entities
  (:require [monkey.ci.entities.migrations :as m]
            [next.jdbc.connection :as conn])
  (:import com.zaxxer.hikari.HikariDataSource))

(def h2-conf {:jdbcUrl "jdbc:h2:mem:monkeyci-repl"})

(defn memory-db-conn []
  {:ds (conn/->pool HikariDataSource h2-conf)})

(defn setup-memory-db! []
  (let [conn (memory-db-conn)]
    (m/run-migrations! (:ds conn))
    conn))
