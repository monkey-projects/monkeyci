(ns monkey.ci.entities.helpers
  "Helper functions for testing database entities"
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [config.core :as cc]
   [monkey.ci.entities.migrations :as m]
   [monkey.ci.spec.entities]
   [monkey.ci.test.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as conn])
  (:import
   (com.zaxxer.hikari HikariDataSource)))

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
               :sql-opts {:dialect :mysql :quoted-snake true}}]
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
      ;; Vault is needed by migrations
      (m/with-migrations (assoc conn :vault (h/dummy-vault))
        #(f conn)))))

(defmacro with-prepared-db [conn & body]
  `(with-prepared-db*
     (fn [~conn]
       ~@body)))

(defn- gen-spec [s]
  (-> (gen/generate (s/gen s))
      ;; id is auto generated
      (dissoc :id)))

(defn gen-customer []
  (gen-spec :db/customer))

(defn gen-repo []
  (gen-spec :db/repo))

(defn gen-build []
  (gen-spec :db/build))

(defn gen-job []
  (gen-spec :db/job))

(defn gen-user []
  (gen-spec :db/user))

(defn gen-ssh-key []
  (gen-spec :db/ssh-key))

(defn gen-customer-param []
  (gen-spec :db/customer-param))

(defn gen-param-value []
  (gen-spec :db/parameter-value))

(defn gen-join-request []
  (gen-spec :db/join-request))

(defn gen-email-registration []
  (gen-spec :db/email-registration))

(defn gen-cust-credit []
  (gen-spec :db/customer-credit))

(defn gen-credit-subscription []
  (gen-spec :db/credit-subscription))

(defn gen-credit-consumption []
  (gen-spec :db/credit-consumption))

(defn gen-webhook []
  (gen-spec :db/webhook))

(defn gen-bb-webhook []
  (gen-spec :db/bb-webhook))

(defn gen-invoice []
  (gen-spec :db/invoice))

(defn gen-queued-task []
  (gen-spec :db/queued-task))
