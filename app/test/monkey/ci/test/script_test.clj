(ns monkey.ci.test.script-test
  (:require [clojure.test :refer :all]
            [monkey.ci
             [containers :as c]
             [script :as sut]]
            [monkey.ci.build.core :as bc]))

(deftest exec-script!
  (testing "executes basic clj script from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-clj"}))))

  (testing "executes script shell from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-script"})))))

(deftest run-pipelines
  (testing "success if no pipelines"
    (is (bc/success? (sut/run-pipelines {} []))))

  (testing "success if all steps succeed"
    (is (bc/success? (->> [(bc/pipeline {:steps [(constantly bc/success)]})]
                          (sut/run-pipelines {})))))

  (testing "runs a single pipline"
    (is (bc/success? (->> (bc/pipeline {:name "single"
                                        :steps [(constantly bc/success)]})
                          (sut/run-pipelines {})))))
  
  (testing "fails if a step fails"
    (is (bc/failed? (->> [(bc/pipeline {:steps [(constantly bc/failure)]})]
                         (sut/run-pipelines {})))))

  (testing "success if step returns `nil`"
    (is (bc/success? (->> (bc/pipeline {:name "nil"
                                        :steps [(constantly nil)]})
                          (sut/run-pipelines {})))))

  (testing "runs pipeline by name, if given"
    (is (bc/success? (->> [(bc/pipeline {:name "first"
                                         :steps [(constantly bc/success)]})
                           (bc/pipeline {:name "second"
                                         :steps [(constantly bc/failure)]})]
                          (sut/run-pipelines {:pipeline "first"}))))))

(defmethod c/run-container :test [ctx]
  {:test-result :run-from-test
   :exit 0})

(deftest pipeline-run-step
  (testing "executes function directly"
    (is (= :ok (sut/run-step (constantly :ok) {}))))

  (testing "executes action from map"
    (is (= :ok (sut/run-step {:action (constantly :ok)} {}))))

  (testing "executes in container if configured"
    (let [config {:container/image "test-image"}
          r (sut/run-step config {:container-runner :test})]
      (is (= :run-from-test (:test-result r)))
      (is (bc/success? r)))))
