(ns monkey.ci.storage.sql-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities.helpers :as eh]
            [monkey.ci
             [protocols :as p]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.entities.core :as ec]
            [monkey.ci.storage.sql :as sut]))

(deftest sql-storage
  (eh/with-prepared-db conn
    (let [s (sut/make-storage conn)]
      (testing "can write and read customer"
        (let [cust {:name "Test customer"
                    :id (st/new-id)}]
          (is (sid/sid? (st/save-customer s cust)))
          (is (= 1 (count (ec/select-customers conn [:is :id [:not nil]]))))
          (is (some? (ec/select-customer conn (ec/by-uuid (parse-uuid (:id cust))))))
          (is (= (assoc cust :repos {})
                 (st/find-customer s (:id cust))))))

      (testing "can write and read customer with repos"
        (let [cust {:name "Another customer"
                    :id (st/new-id)}
              repo {:name "test repo"
                    :customer-id (:id cust)
                    :id "test-repo"
                    :url "http://test-repo"}]
          (is (sid/sid? (st/save-customer s cust)))
          (is (sid/sid? (st/save-repo s repo)))
          (is (= (assoc cust :repos {(:id repo) (dissoc repo :customer-id)})
                 (st/find-customer s (:id cust))))))

      (testing "can delete customer with repos"
        (let [cust {:name "Another customer"
                    :id (st/new-id)}
              repo {:name "test repo"
                    :customer-id (:id cust)
                    :id "test-repo"
                    :url "http://test-repo"}]
          (is (sid/sid? (st/save-customer s cust)))
          (is (sid/sid? (st/save-repo s repo)))
          (is (some? (st/find-customer s (:id cust))))
          (is (true? (p/delete-obj s (st/customer-sid (:id cust)))))
          (is (nil? (st/find-customer s (:id cust)))))))))
