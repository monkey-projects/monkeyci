(ns monkey.ci.entities.helpers
  "Helper functions for testing database entities"
  (:require [config.core :as cc]
            [monkey.ci.entities.migrations :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn])
  (:import com.zaxxer.hikari.HikariDataSource))

(defn db-config
  "Takes db config from env"
  []
  {:jdbcUrl (get cc/env :test-db-url)
   :username (get cc/env :test-db-username)
   :password (get cc/env :test-db-password)})

(def h2-config
  {:jdbcUrl "jdbc:h2:mem:"
   :username "SA"
   :password ""})

(defn- test-config
  "Either takes db configuration from env, or memory db"
  []
  (let [c (db-config)]
    (if (empty? (:jdbcUrl c))
      h2-config
      c)))

(defn with-test-db*
  ([f conf]
   (let [conn {:ds (conn/->pool HikariDataSource conf)
               :sql-opts {} #_{:dialect :ansi}}]
     (try
       (f conn)
       (finally
         (.close (:ds conn))))))
  ([f]
   (with-test-db* f (test-config))))

(defn with-memory-db* [f]
  (with-test-db* f h2-config))

(defn with-prepared-db* [f]
  (with-test-db*
    (fn [conn]
      (m/with-migrations (:ds conn)
        #(f conn)))))

(defmacro with-prepared-db [conn & body]
  `(with-prepared-db*
     (fn [~conn]
       ~@body)))
