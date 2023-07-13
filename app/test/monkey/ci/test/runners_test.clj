(ns monkey.ci.test.runners-test
  (:require [clojure.test :refer :all]
            [monkey.ci
             [process :as p]
             [runners :as sut]]))

(deftest make-runner
  (testing "creates local runner"
    (is (fn? (sut/make-runner {:runner {:type :local}}))))

  (testing "creates local runner by default"
    (is (fn? (sut/make-runner {})))))

(deftest local-runner
  (testing "runs script locally in child process"
    (with-redefs [p/execute! (constantly {:exit ::ok})]
      (is (= ::ok (sut/local-runner {:script {:dir "examples/basic-clj"}}))))))
