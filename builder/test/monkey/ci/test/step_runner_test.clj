(ns monkey.ci.test.step-runner-test
  (:require [clojure.test :refer :all]
            [monkey.ci.step-runner :as sut]))

(deftest step-runner
  (testing "fn implements it"
    (letfn [(testfn [ctx]
              :ok)]
      (is (= :ok (sut/run-step testfn :test-ctx))))))

