(ns monkey.ci.entities.migrations-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [monkey.ci.cuid :as cuid]
            [monkey.ci.entities
             [core :as ec]
             [helpers :as eh]
             [migrations :as sut]]
            [monkey.ci.test.helpers :as h]
            [monkey.ci.vault :as v]
            [monkey.ci.web.crypto :as crypto]
            [ragtime
             [core :as rc]
             [next-jdbc :as rj]
             [protocols :as rp]]))

(defn- gen-org []
  (-> (eh/gen-org)
      (dissoc :display-id)))

(deftest fk
  (testing "creates foreign key constraint with cascading"
    (is (= "CREATE TABLE test (FOREIGN KEY(src_col) REFERENCES dest_table(dest_col) ON DELETE CASCADE)"
           (-> {:create-table :test
                :with-columns [(sut/fk :src-col :dest-table :dest-col)]}
               sql/format
               first))))

  (testing "no cascading delete when specified"
    (is (= "CREATE TABLE test (FOREIGN KEY(src_col) REFERENCES dest_table(dest_col))"
           (-> {:create-table :test
                :with-columns [(sut/fk :src-col :dest-table :dest-col true)]}
               sql/format
               first)))))

(defn with-migrations-up-to*
  "Executes migrations up to given predicate, then invokes `f` with connection
   and the next migration"
  [p f]
  (eh/with-test-db*
    (fn [{:keys [ds] :as conn}]
      (let [[migs rem] (->> (sut/prepare-migrations sut/migrations (-> conn
                                                                       (eh/add-vault)
                                                                       (dissoc :ds)))
                            (split-with (complement p)))
            db (rj/sql-database ds)
            idx (rc/into-index migs)]
        (rc/migrate-all db idx migs)
        (try
          (f conn (first rem))
          (finally
            ;; Rollback all
            (rc/rollback-last db idx (count migs))))))))

(defmacro with-migrations-up-to [p conn mig & body]
  `(with-migrations-up-to* ~p
     (fn [~conn ~mig]
       ~@body)))

;; (defn- insert-customer [conn cust]
;;   (ec/insert-entity conn :customers cust))

;; (defn- insert-customer-param [conn p]
;;   (ec/insert-entity conn :customer-params (assoc p :cuid (cuid/random-cuid))))

;; (deftest ^:sql customer-ivs
;;   (testing "creates crypto record for each customer that does not have one yet"
;;     ;; Since customer table is renamed in later migrations, we can only run up
;;     ;; to the customer-ivs migration for testing.
;;     (with-migrations-up-to (comp (partial re-matches #"^.*-create-customer-ivs$")
;;                                  rp/id)
;;         conn mig
;;         (let [cust (eh/gen-customer)
;;               cust-id (:id (insert-customer conn cust))]
;;           (is (number? cust-id))
;;           (is (nil? ((:up mig) conn)))
;;           (is (= 1 (count (ec/select-cryptos conn [:= :customer-id cust-id]))))))))

;; (deftest ^:sql encrypt-params
;;   (with-migrations-up-to (comp (partial re-matches #"^.*-encrypt-params$")
;;                                rp/id)
;;       conn mig
;;       (let [vault (h/dummy-vault (constantly "encrypted")
;;                                  (constantly "decrypted"))
;;             conn (assoc conn :vault vault)
;;             cust (eh/gen-customer)
;;             cust-id (:id (insert-customer conn cust))
;;             param (insert-customer-param
;;                    conn
;;                    {:customer-id cust-id})
;;             pv {:name "test-param"
;;                 :value "test-value"}]
;;         (is (some? (ec/insert-customer-param-value
;;                     conn
;;                     (assoc pv :params-id (:id param)))))
;;         (is (some? (ec/insert-crypto
;;                     conn
;;                     {:org-id cust-id
;;                      :iv (v/generate-iv)})))
      
;;         (testing "`up` encrypts all customer parameter values"
;;           (is (some? ((:up mig) conn)))
;;           (is (= "encrypted" (-> (ec/select-customer-param-values conn [:= :params-id (:id param)])
;;                                  first
;;                                  :value))))

;;         (testing "`down` decrypts all customer parameter values"
;;           (is (some? ((:down mig) conn)))
;;           (is (= "decrypted" (-> (ec/select-customer-param-values conn [:= :params-id (:id param)])
;;                                  first
;;                                  :value)))))))

;; (deftest ^:sql encrypt-ssh-keys
;;   (eh/with-prepared-db conn
;;     (let [mig (sut/encrypt-ssh-keys 1)
;;           vault (h/dummy-vault (constantly "encrypted")
;;                                (constantly "decrypted"))
;;           conn (assoc conn :vault vault)
;;           cust (eh/gen-customer)
;;           cust-id (:id (ec/insert-customer conn cust))
;;           ssh-key {:private-key "test-pk"
;;                    :public-key "test-pubkey"}]
;;       (is (some? (ec/insert-ssh-key
;;                   conn
;;                   (assoc ssh-key :org-id cust-id))))
;;       (is (some? (ec/insert-crypto
;;                   conn
;;                   {:org-id cust-id
;;                    :iv (v/generate-iv)})))
      
;;       (testing "`up` encrypts all customer parameter values"
;;         (is (some? ((:up mig) conn)))
;;         (is (= "encrypted" (-> (ec/select-ssh-keys conn [:= :org-id cust-id])
;;                                first
;;                                :private-key))))

;;       (testing "`down` decrypts all customer parameter values"
;;         (is (some? ((:down mig) conn)))
;;         (is (= "decrypted" (-> (ec/select-ssh-keys conn [:= :org-id cust-id])
;;                                first
;;                                :private-key)))))))

;; (deftest ^:sql calc-next-idx
;;   (eh/with-prepared-db conn
;;     (let [mig (sut/calc-next-idx 1)
;;           cust (eh/gen-customer)
;;           cust-id (:id (ec/insert-customer conn cust))
;;           repo (-> (eh/gen-repo)
;;                    (assoc :org-id cust-id))
;;           repo-id (:id (ec/insert-repo conn repo))]

;;       (is (some? (ec/insert-build
;;                   conn
;;                   (assoc (eh/gen-build)
;;                          :repo-id repo-id
;;                          :idx 100))))

;;       (testing "`up` creates repo-indices record for each repo with next build idx"
;;         (is (some? ((:up mig) conn)))
;;         (let [all (ec/select-repo-indices conn nil)]
;;           (is (= 1 (count all)))))

;;       (testing "`down` deletes all records in repo-indices table"
;;         (is (some? ((:down mig) conn)))
;;         (is (empty? (ec/select-repo-indices conn nil)))))))

(deftest ^:sql generate-org-deks
  (with-migrations-up-to (comp (partial re-matches #"^.*-generate-org-deks$")
                               rp/id)
    conn mig
    (is (some? mig))
    (let [org (ec/insert-org conn (gen-org))
          crypto (ec/insert-crypto conn
                                   {:org-id (:id org)
                                    :iv (v/generate-iv)})
          conn (assoc conn :crypto {:dek-generator (fn [org-id]
                                                     {:enc (str "test-dek for " org-id)
                                                      :key "plain-key"})})]
      (is (number? (:id org)))
      (is (some? crypto))

      (testing "`up` generates new AES key"
        (is (some? ((:up mig) conn)))
        (let [c (ec/select-cryptos conn [:= :org-id (:id org)])]
          (is (every? (partial = (str "test-dek for " (:id org))) (map :dek c)))))

      (testing "`down` does nothing"
        (is (nil? ((:down mig) conn)))))))

(deftest ^:sql re-encrypt-params
  (with-migrations-up-to (comp (partial re-matches #"^.*-re-encrypt-params$")
                               rp/id)
    conn mig
    (is (some? mig))
    (let [org (ec/insert-org conn (gen-org))
          pv (ec/insert-org-param conn {:org-id (:id org)})
          crypto (ec/insert-crypto conn
                                   {:org-id (:id org)
                                    :iv (v/generate-iv)})
          conn (assoc conn
                      :crypto {:encrypter (fn [p org-id id]
                                            (when (= "vault-decrypted" p)
                                              "dek-encrypted"))
                               :decrypter (fn [p org-id id]
                                            (when (= "dek-encrypted" p)
                                              "dek-decrypted"))}
                      :vault (h/dummy-vault (fn [obj]
                                              (when (= "dek-decrypted" obj)
                                                "vault-encrypted"))
                                            (fn [enc]
                                              (when (= "vault-encrypted" enc)
                                                "vault-decrypted"))))]
      (is (number? (:id org)))
      (is (some? crypto))
      (is (some? pv))
      (is (some? (ec/insert-org-param-value conn
                                            {:params-id (:id pv)
                                             :name "test-param"
                                             :value "vault-encrypted"})))

      (testing "`up` uses org dek to re-encrypt param values"
        (is (some? ((:up mig) conn)))
        (is (= "dek-encrypted"
               (-> (ec/select-org-param-values conn nil)
                   first
                   :value))))
      
      (testing "`down` uses vault to re-encrypt param values"
        (is (some? ((:down mig) conn)))
        (is (= "vault-encrypted"
               (-> (ec/select-org-param-values conn nil)
                   first
                   :value)))))))

(deftest ^:sql re-encrypt-ssh-keys
  (with-migrations-up-to (comp (partial re-matches #"^.*-re-encrypt-ssh-keys$")
                               rp/id)
      conn mig
    (is (some? mig))
    (let [org (ec/insert-org conn (gen-org))
          sk (ec/insert-ssh-key conn {:org-id (:id org)
                                      :public-key "test-pubkey"
                                      :private-key "vault-encrypted"})
          crypto (ec/insert-crypto conn
                                   {:org-id (:id org)
                                    :iv (v/generate-iv)})
          conn (assoc conn
                      :crypto {:encrypter (fn [p org-id id]
                                            (when (= "vault-decrypted" p)
                                              "dek-encrypted"))
                               :decrypter (fn [p org-id id]
                                            (when (= "dek-encrypted" p)
                                              "dek-decrypted"))}
                      :vault (h/dummy-vault (fn [obj]
                                              (when (= "dek-decrypted" obj)
                                                "vault-encrypted"))
                                            (fn [enc]
                                              (when (= "vault-encrypted" enc)
                                                "vault-decrypted"))))]
      (is (number? (:id org)))
      (is (some? crypto))
      (is (some? sk))

      (testing "`up` uses org dek to re-encrypt private keys"
        (is (some? ((:up mig) conn)))
        (is (= "dek-encrypted"
               (-> (ec/select-ssh-keys conn nil)
                   first
                   :private-key))))
      
      (testing "`down` uses vault to re-encrypt ssh-keys"
        (is (some? ((:down mig) conn)))
        (is (= "vault-encrypted"
               (-> (ec/select-ssh-keys conn nil)
                   first
                   :private-key)))))))
