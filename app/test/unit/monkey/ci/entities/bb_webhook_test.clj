(ns monkey.ci.entities.bb-webhook-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities.bb-webhook :as sut]))

(deftest by-filter
  (testing "creates where clause for org id"
    (is (= [[:= :c.cuid "test-org"]]
           (sut/by-filter {:org-id "test-org"}))))

  (testing "creates where clause for org and repo id"
    (is (= [:and
            [:= :c.cuid "test-org"]
            [:= :r.display-id "test-repo"]]
           (sut/by-filter {:org-id "test-org"
                           :repo-id "test-repo"})))))

