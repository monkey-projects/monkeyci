(ns monkey.ci.spec.job-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.job :as sut]))

(deftest job-spec
  (testing "action job"
    (testing "accepts basic job"
      (let [job {:id "test-job"
                 :spec {:type :action
                        :action (constantly nil)}}]
        (is (= job (s/conform ::sut/job job)))))

    (testing "accepts job with artifacts"
      (let [job {:id "test-job"
                 :spec {:type :action
                        :action (constantly nil)
                        :save-artifacts [{:id "test-art"
                                          :path "/test/path"}]}}]
        (is (= job (s/conform ::sut/job job)))))

    (testing "accepts job with dependencies"
      (let [job {:id "test-job"
                 :spec {:type :action
                        :action (constantly nil)
                        :dependencies ["other-job"]}}]
        (is (= job (s/conform ::sut/job job)))))

    (testing "accepts job with status"
      (let [job {:id "test-job"
                 :spec {:type :action
                        :action (constantly nil)}
                 :status {:lifecycle :running
                          :runner {:type :test-runner}}}]
        (is (= job (s/conform ::sut/job job))))))

  (testing "does not accept unknown type"
    (is (not (s/valid? ::sut/job {:id "invalid-job"
                                  :spec {:type :unknown}})))))
