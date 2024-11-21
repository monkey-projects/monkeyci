(ns monkey.ci.test-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.test :as sut]))

(deftest test-ctx
  (testing "is a context map"
    (is (map? sut/test-ctx)))

  (testing "contains a basic build"
    (is (some? (:build sut/test-ctx)))))
