(ns monkey.ci.entities.helpers
  "Helper functions for testing database entities"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [config.core :as cc]
            [monkey.ci.entities.migrations :as m]
            [monkey.ci.entities.spec :as spec]
            [monkey.ci.test.helpers :as h]
            [next.jdbc.connection :as conn])
  (:import (com.zaxxer.hikari HikariDataSource)))

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
               :sql-opts {:dialect :ansi :quoted-snake true}}]
     (try
       (f conn)
       (finally
         (.close (:ds conn))))))
  ([f]
   (with-test-db* f (test-config))))

(defn with-memory-db* [f]
  (with-test-db* f h2-config))

(defn add-vault [conn]
  (assoc conn :vault (h/dummy-vault)))

(defn with-prepared-db* [f]
  (with-test-db*
    (fn [conn]
      ;; Vault is needed by migrations
      (m/with-migrations (add-vault conn)
        #(f conn)))))

(defmacro with-prepared-db [conn & body]
  `(with-prepared-db*
     (fn [~conn]
       ~@body)))

(defn- gen-spec [s]
  (-> (gen/generate (s/gen s))
      ;; id is auto generated
      (dissoc :id)))

(defn gen-org []
  (gen-spec ::spec/org))

(def ^:deprecated gen-customer gen-org)

(defn gen-repo []
  (gen-spec ::spec/repo))

(defn gen-build []
  (gen-spec ::spec/build))

(defn gen-job []
  (gen-spec ::spec/job))

(defn gen-user []
  (gen-spec ::spec/user))

(defn gen-ssh-key []
  (gen-spec ::spec/ssh-key))

(defn gen-org-param []
  (gen-spec ::spec/org-param))

(def ^:deprecated gen-customer-param gen-org-param)

(defn gen-param-value []
  (gen-spec ::spec/parameter-value))

(defn gen-join-request []
  (gen-spec ::spec/join-request))

(defn gen-email-registration []
  (gen-spec ::spec/email-registration))

(defn gen-org-credit []
  (gen-spec ::spec/org-credit))

(def ^:deprecated gen-cust-credit gen-org-credit)

(defn gen-credit-subscription []
  (gen-spec ::spec/credit-subscription))

(defn gen-credit-consumption []
  (gen-spec ::spec/credit-consumption))

(defn gen-webhook []
  (gen-spec ::spec/webhook))

(defn gen-bb-webhook []
  (gen-spec ::spec/bb-webhook))

(defn gen-invoice []
  (gen-spec ::spec/invoice))

(defn gen-queued-task []
  (gen-spec ::spec/queued-task))

(defn gen-job-evt []
  (gen-spec ::spec/job-event))

(defn gen-user-token []
  (gen-spec ::spec/user-token))

(defn gen-user-settings []
  (gen-spec ::spec/user-setting))

(defn gen-org-token []
  (gen-spec ::spec/org-token))

(defn gen-mailing []
  (gen-spec ::spec/mailing))

(defn gen-sent-mailing []
  (gen-spec ::spec/sent-mailing))

(defn gen-org-invoicing []
  (gen-spec ::spec/org-invoicing))
