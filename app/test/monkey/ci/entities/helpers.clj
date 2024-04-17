(ns monkey.ci.entities.helpers
  "Helper functions for testing database entities"
  (:require [monkey.ci.entities.migrations :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn])
  (:import com.zaxxer.hikari.HikariDataSource))

(defn with-memory-db* [f]
  (let [conf {:jdbcUrl (str "jdbc:hsqldb:mem:test-" (random-uuid))
              :username "SA"
              :password ""}
        conn {:ds (conn/->pool HikariDataSource conf)
              :sql-opts {} #_{:dialect :ansi}}]
    (try
      (f conn)
      (finally
        ;; This also drops the migrations table
        #_(jdbc/execute! ds ["truncate schema public and commit"])))))

(defn with-prepared-db* [f]
  (with-memory-db*
    (fn [conn]
      (m/run-migrations! (:ds conn))
      (f conn))))

(defmacro with-prepared-db [conn & body]
  `(with-prepared-db*
     (fn [~conn]
       ~@body)))
