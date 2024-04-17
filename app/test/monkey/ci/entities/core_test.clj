(ns monkey.ci.entities.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as sut]
             [helpers :as eh]]
            [next.jdbc :as jdbc]))

(deftest memory-db
  (testing "can connect"
    (eh/with-memory-db*
      (fn [conn]
        (is (some? conn))))))

(deftest prepared-db
  (testing "has customers table"
    (eh/with-prepared-db*
      (fn [conn]
        (is (number? (-> (jdbc/execute-one! (:ds conn) ["select count(*) as c from customers"])
                         :C)))))))

(deftest customer-entities
  (eh/with-prepared-db conn
    (let [cust {:name "test customer"}
          r (sut/insert-customer conn cust)]
      (testing "can insert"
        (is (some? (:uuid r)))
        (is (number? (:id r)))
        (is (= cust (select-keys r (keys cust)))))

      (testing "can select by id"
        (is (= r (sut/select-customer conn (sut/by-id (:id r))))))

      (testing "can select by uuid"
        (is (= r (sut/select-customer conn (sut/by-uuid (:uuid r))))))

      (testing "can update"
        (is (= 1 (sut/update-customer conn (assoc r :name "updated"))))
        (is (= "updated" (:name (sut/select-customer conn (sut/by-id (:id r)))))))

      (testing "cannot update nonexisting"
        (is (zero? (sut/update-customer conn {:id -1 :name "nonexisting customer"})))))

    (testing "throws on invalid record"
      (is (thrown? Exception (sut/insert-customer conn {}))))))

(deftest repo-entities
  (eh/with-prepared-db conn
    (let [cust (sut/insert-customer conn {:name "test customer"})
          r (sut/insert-repo conn {:name "test repo"
                                   :customer-id (:id cust)
                                   :url "http://test"})]
      (testing "can insert"
        (is (some? (:uuid r)))
        (is (number? (:id r)))
        (is (= "test repo" (:name r))))

      (testing "can select by id"
        (is (= r (sut/select-repo conn (sut/by-id (:id r))))))

      (testing "can select by uuid"
        (is (= r (sut/select-repo conn (sut/by-uuid (:uuid r)))))))

    (testing "throws on invalid record"
      (is (thrown? Exception (sut/insert-repo conn {:name "customerless repo"}))))))
