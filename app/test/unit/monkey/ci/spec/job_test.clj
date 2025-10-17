(ns monkey.ci.spec.job-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.job :as sut]))

(deftest job-spec
  (testing "accepts basic job"
    (let [job {:id "test-job"
               :type :action}]
      (is (= job (s/conform ::sut/job job)))))

  (testing "accepts job with artifacts"
    (let [job {:id "test-job"
               :type :action
               :save-artifacts [{:id "test-art"
                                 :path "/test/path"}]}]
      (is (= job (s/conform ::sut/job job)))))

  (testing "accepts job with dependencies"
    (let [job {:id "test-job"
               :type :action
               :dependencies ["other-job"]}]
      (is (= job (s/conform ::sut/job job)))))

  (testing "accepts job with status"
    (let [job {:id "test-job"
               :type :action
               :status {:lifecycle :running
                        :runner {:type :test-runner}}}]
      (is (= job (s/conform ::sut/job job)))))

  (testing "does not accept unknown type"
    (is (not (s/valid? ::sut/job {:id "invalid-job"
                                  :type :unknown})))))
