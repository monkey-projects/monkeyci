(ns monkey.ci.test.artifacts-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci
             [artifacts :as sut]
             [blob :as blob]]
            [monkey.ci.test.helpers :as h]))

(deftest save-artifacts
  (testing "saves path using blob store"
    (let [stored (atom {})
          bs (h/->FakeBlobStore stored)
          ctx {:artifacts {:store bs}
               :build {:sid ["test-cust" "test-build"]}
               :step {:work-dir "work"
                      :save-artifacts [{:id "test-artifact"
                                        :path "test-path"}]}}]
      (is (some? @(sut/save-artifacts ctx)))
      (is (= 1 (count @stored)))
      (is (cs/ends-with? (-> @stored first second)
                         "test-cust/test-build/test-artifact.tgz"))))

  (testing "nothing if no cache store"
    (is (empty? @(sut/save-artifacts
                  {:step {:save-artifacts [{:id "test-artifact"
                                            :path "test-path"}]}})))))

(deftest restore-artifacts
  (testing "restores path using blob store"
    (let [stored (atom {"test-cust/test-build/test-artifact.tgz" ::dest})
          bs (h/->FakeBlobStore stored)
          ctx {:artifacts {:store bs}
               :build {:sid ["test-cust" "test-build"]}
               :step {:work-dir "work"
                      :restore-artifacts [{:id "test-artifact"
                                           :path "test-path"}]}}]
      (is (some? @(sut/restore-artifacts ctx)))
      (is (empty? @stored)))))
