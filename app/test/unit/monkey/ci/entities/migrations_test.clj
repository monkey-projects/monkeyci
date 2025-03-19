(ns monkey.ci.entities.migrations-test
  (:require [clojure.test :refer [deftest is testing]]
            [honey.sql :as sql]
            [monkey.ci.entities
             [core :as ec]
             [helpers :as eh]
             [migrations :as sut]]
            [monkey.ci.test.helpers :as h]
            [monkey.ci.vault :as v]))

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

(deftest ^:sql customer-ivs
  (testing "creates crypto record for each customer that does not have one yet"
    (eh/with-prepared-db conn
      (let [mig (sut/customer-ivs 1)
            cust (eh/gen-customer)
            cust-id (:id (ec/insert-customer conn cust))]
        (is (number? cust-id))
        (is (nil? ((:up mig) conn)))
        (is (= 1 (count (ec/select-cryptos conn (ec/by-customer cust-id)))))))))

(deftest ^:sql encrypt-params
  (eh/with-prepared-db conn
    (let [mig (sut/encrypt-params 1)
          vault (h/dummy-vault (constantly "encrypted")
                               (constantly "decrypted"))
          conn (assoc conn :vault vault)
          cust (eh/gen-customer)
          cust-id (:id (ec/insert-customer conn cust))
          param (ec/insert-customer-param
                 conn
                 {:customer-id cust-id})
          pv {:name "test-param"
              :value "test-value"}]
      (is (some? (ec/insert-customer-param-value
                  conn
                  (assoc pv :params-id (:id param)))))
      (is (some? (ec/insert-crypto
                  conn
                  {:customer-id cust-id
                   :iv (v/generate-iv)})))
      
      (testing "`up` encrypts all customer parameter values"
        (is (some? ((:up mig) conn)))
        (is (= "encrypted" (-> (ec/select-customer-param-values conn [:= :params-id (:id param)])
                               first
                               :value))))

      (testing "`down` decrypts all customer parameter values"
        (is (some? ((:down mig) conn)))
        (is (= "decrypted" (-> (ec/select-customer-param-values conn [:= :params-id (:id param)])
                               first
                               :value)))))))

(deftest ^:sql encrypt-ssh-keys
  (eh/with-prepared-db conn
    (let [mig (sut/encrypt-ssh-keys 1)
          vault (h/dummy-vault (constantly "encrypted")
                               (constantly "decrypted"))
          conn (assoc conn :vault vault)
          cust (eh/gen-customer)
          cust-id (:id (ec/insert-customer conn cust))
          ssh-key {:private-key "test-pk"
                   :public-key "test-pubkey"}]
      (is (some? (ec/insert-ssh-key
                  conn
                  (assoc ssh-key :customer-id cust-id))))
      (is (some? (ec/insert-crypto
                  conn
                  {:customer-id cust-id
                   :iv (v/generate-iv)})))
      
      (testing "`up` encrypts all customer parameter values"
        (is (some? ((:up mig) conn)))
        (is (= "encrypted" (-> (ec/select-ssh-keys conn [:= :customer-id cust-id])
                               first
                               :private-key))))

      (testing "`down` decrypts all customer parameter values"
        (is (some? ((:down mig) conn)))
        (is (= "decrypted" (-> (ec/select-ssh-keys conn [:= :customer-id cust-id])
                               first
                               :private-key)))))))

(deftest ^:sql calc-next-idx
  (eh/with-prepared-db conn
    (let [mig (sut/calc-next-idx 1)
          cust (eh/gen-customer)
          cust-id (:id (ec/insert-customer conn cust))
          repo (-> (eh/gen-repo)
                   (assoc :customer-id cust-id))
          repo-id (:id (ec/insert-repo conn repo))]

      (is (some? (ec/insert-build
                  conn
                  (assoc (eh/gen-build)
                         :repo-id repo-id
                         :idx 100))))

      (testing "`up` creates repo-indices record for each repo with next build idx"
        (is (some? ((:up mig) conn)))
        (let [all (ec/select-repo-indices conn nil)]
          (is (= 1 (count all)))))

      (testing "`down` deletes all records in repo-indices table"
        (is (some? ((:down mig) conn)))
        (is (empty? (ec/select-repo-indices conn nil)))))))
