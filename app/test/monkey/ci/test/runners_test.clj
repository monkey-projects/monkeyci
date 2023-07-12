(ns monkey.ci.test.runners-test
  (:require [clojure.test :refer :all]
            [monkey.ci.runners :as sut]))

(deftest make-runner
  (testing "creates local runner"
    (is (fn? (sut/make-runner {:runner {:type :local}}))))

  (testing "creates local runner by default"
    (is (fn? (sut/make-runner {})))))

(deftest local-runner
  (testing "runs script locally"
    (is (zero? (sut/local-runner {:script {:dir "examples/basic-clj"}})))))
