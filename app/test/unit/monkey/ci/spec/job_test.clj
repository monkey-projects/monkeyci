(ns monkey.ci.spec.job-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.job :as sut]
            [monkey.ci.spec.job
             [v1 :as v1]
             [v2 :as v2]]))

(deftest v1-spec
  (testing "action job"
    (testing "accepts basic job"
      (let [job {:id "test-job"
                 :type :action
                 :action (constantly nil)}]
        (is (= job (s/conform ::v1/job job)))))

    (testing "accepts job with artifacts"
      (let [job {:id "test-job"
                 :type :action
                 :action (constantly nil)
                 :save-artifacts [{:id "test-art"
                                   :path "/test/path"}]}]
        (is (= job (s/conform ::v1/job job)))))

    (testing "accepts job with dependencies"
      (let [job {:id "test-job"
                 :type :action
                 :action (constantly nil)
                 :dependencies ["other-job"]}]
        (is (= job (s/conform ::v1/job job)))))

    (testing "accepts job with status"
      (let [job {:id "test-job"
                 :type :action
                 :action (constantly nil)
                 :status :running
                 :credit-multiplier 1}]
        (is (= job (s/conform ::v1/job job))))))

  (testing "container job"
    (testing "requires image"
      (is (s/valid? ::v1/job {:id "container-job"
                              :type :container
                              :image "test-img"}))

      (is (not (s/valid? ::v1/job {:id "container-job"
                                   :type :container}))))

    (testing "allows size"
      (is (s/valid? ::v1/job {:id "sized-job"
                              :type :container
                              :image "test-img"
                              :size 1})))

    (testing "allows cpus and memory"
      (is (s/valid? ::v1/job {:id "mem-job"
                              :type :container
                              :image "test-img"
                              :cpus 1
                              :memory 2}))))

  (testing "does not accept unknown type"
    (is (not (s/valid? ::v1/job {:id "invalid-job"
                                 :type :unknown})))))

(deftest v2-spec
  (testing "action job"
    (testing "accepts basic job"
      (let [job {:id "test-job"
                 :schema :v2
                 :spec {:type :action
                        :action (constantly nil)}}]
        (is (= job (s/conform ::v2/job job)))))

    (testing "accepts job with artifacts"
      (let [job {:id "test-job"
                 :schema :v2
                 :spec {:type :action
                        :action (constantly nil)
                        :save-artifacts [{:id "test-art"
                                          :path "/test/path"}]}}]
        (is (= job (s/conform ::v2/job job)))))

    (testing "accepts job with dependencies"
      (let [job {:id "test-job"
                 :schema :v2
                 :spec {:type :action
                        :action (constantly nil)
                        :dependencies ["other-job"]}}]
        (is (= job (s/conform ::v2/job job)))))

    (testing "accepts job with status"
      (let [job {:id "test-job"
                 :schema :v2
                 :spec {:type :action
                        :action (constantly nil)}
                 :status {:lifecycle :running
                          :runner {:type :test-runner}
                          :credit-multiplier 1}}]
        (is (= job (s/conform ::v2/job job))))))

  (testing "container job"
    (testing "requires image"
      (is (s/valid? ::v2/job {:id "container-job"
                              :schema :v2
                              :spec {:type :container
                                     :image "test-img"}}))

      (is (not (s/valid? ::v2/job {:id "container-job"
                                   :schema :v2
                                   :spec {:type :container
                                          :script ["some" "script"]}}))))

    (testing "allows size"
      (is (s/valid? ::v2/job {:id "sized-job"
                              :schema :v2
                              :spec {:type :container
                                     :image "test-img"
                                     :size 1}}))))

  (testing "does not accept unknown type"
    (is (not (s/valid? ::v2/job {:id "invalid-job"
                                 :schema :v2
                                 :spec {:type :unknown}})))))

(deftest job-spec
  (testing "uses `schema` property to determine job spec"
    (is (s/valid? ::sut/job
                  {:id "no-schema-is-v1"
                   :type :action
                   :action (constantly nil)}))

    (is (s/valid? ::sut/job
                  {:id "v1-job"
                   :schema :v1
                   :type :action
                   :action (constantly nil)}))

    (is (s/valid? ::sut/job
                  {:id "v2-job"
                   :schema :v2
                   :spec {:type :action
                          :action (constantly nil)}}))))
