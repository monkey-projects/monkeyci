(ns monkey.ci.storage.sql-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.storage.sql :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn])
  (:import com.zaxxer.hikari.HikariDataSource))

(defn with-memory-db* [f]
  (let [conf {:jdbcUrl (str "jdbc:hsqldb:mem:test-" (random-uuid))
              :username "SA"
              :password ""}
        ds (conn/->pool HikariDataSource conf)]
    (try
      (f ds)
      (finally
        ;; This also drops the migrations table
        #_(jdbc/execute! ds ["truncate schema public and commit"])))))

(defn with-prepared-db* [f]
  (with-memory-db*
    (fn [ds]
      (sut/run-migrations! ds)
      (f ds))))

(deftest memory-db
  (testing "can connect"
    (with-memory-db*
      (fn [conn]
        (is (some? conn))))))

(deftest prepared-db
  (testing "has customers table"
    (with-prepared-db*
      (fn [conn]
        (is (number? (-> (jdbc/execute-one! conn ["select count(*) as c from customers"])
                         :C)))))))

(defn with-sql-store* [f]
  (with-prepared-db*
    (fn [conn]
      (let [s (sut/->SqlStorage conn)]
        (f s)))))

(defmacro with-sql-store [st & body]
  `(with-sql-store*
     (fn [~st]
       ~@body)))

#_(deftest ^:integration mysql-migrations
  (testing "has customers table"
    ;; TODO Configure mysql from env
    (with-prepared-db*
      (fn [conn]
        (is (number? (-> (jdbc/execute-one! conn ["select count(*) as c from customers"])
                         :C)))))))

(deftest sid->table
  (testing "for customers"
    (is (= :customers (sut/sid->table (st/customer-sid (random-uuid))))))

  (testing "for repos"
    (is (= :repos (sut/sid->table (repeatedly 2 random-uuid))))))

(deftest sql-storage
  (letfn [(count-customers [st]
            (count (p/list-obj st [st/global "customers"])))]
    (testing "can write and read customer"
      (with-sql-store st
        (let [cust {:id (random-uuid)
                    :name "test customer"}
              n (count-customers st)]
          (is (st/sid? (st/save-customer st cust)))
          (let [m (st/find-customer st (:id cust))]
            (is (= cust m)))
          (is (= 1 (- (count-customers st) n))))))

    (testing "updates existing customer"
      (with-sql-store st
        (let [cust {:id (random-uuid)
                    :name "test customer"}
              n (count-customers st)]
          (is (st/sid? (st/save-customer st cust)))
          (is (st/sid? (st/save-customer st (assoc cust :name "updated"))))
          (is (= 1 (- (count-customers st) n)))))))

  (testing "can write and read repo"
    (with-sql-store st
      (let [cust {:id (random-uuid)
                  :name "test customer"}
            repo {:customer-id (:id cust)
                  :id (random-uuid)
                  :name "test repo"
                  :url "http://test-url"}]
        (is (st/sid? (st/save-repo st repo)))))))
