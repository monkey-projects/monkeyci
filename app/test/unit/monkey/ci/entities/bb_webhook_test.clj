(ns monkey.ci.entities.bb-webhook-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities.bb-webhook :as sut]))

(deftest by-filter
  (testing "creates where clause for customer id"
    (is (= [[:= :c.cuid "test-cust"]]
           (sut/by-filter {:customer-id "test-cust"}))))

  (testing "creates where clause for customer and repo id"
    (is (= [:and
            [:= :c.cuid "test-cust"]
            [:= :r.display-id "test-repo"]]
           (sut/by-filter {:customer-id "test-cust"
                           :repo-id "test-repo"})))))

