(ns monkey.ci.metrics.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.metrics.common :as sut]))

(deftest counter-id
  (testing "builds metrics name from parts"
    (is (= "monkeyci_test_metric" (sut/counter-id [:test :metric])))))

