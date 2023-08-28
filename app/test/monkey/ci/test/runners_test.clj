(ns monkey.ci.test.runners-test
  (:require [clojure.test :refer :all]
            [monkey.ci
             [process :as p]
             [runners :as sut]]))

(deftest make-runner
  (testing "creates local runner"
    (is (= sut/local-runner (sut/make-runner {:runner {:type :local}}))))

  (testing "creates local runner by default"
    (is (= sut/local-runner (sut/make-runner {}))))

  (testing "supports noop runner"
    (is (fn? (sut/make-runner {:runner {:type :noop}})))))

(deftest local-runner
  (testing "runs script locally in child process, returns exit code"
    (with-redefs [p/execute! (constantly {:exit ::ok})]
      (is (= ::ok (sut/local-runner {:script {:dir "examples/basic-clj"}}))))))
