(ns monkey.ci.common.schemas-test
  (:require [clojure.test :refer [deftest testing is]]
            [java-time.api :as jt]
            [monkey.ci.common.schemas :as sut]))

(deftest period-pattern
  (testing "matches valid periods"
    (is (re-matches sut/period-pattern "P2Y"))
    (is (re-matches sut/period-pattern "P10M"))
    (is (re-matches sut/period-pattern "P2Y2M2D")))

  (testing "does not match invalid patterns"
    (is (not (re-matches sut/period-pattern "invalid")))
    (is (not (re-matches sut/period-pattern "P1Y with invalid"))))

  (testing "can be parsed"
    (is (some? (jt/period "P2Y")))))
