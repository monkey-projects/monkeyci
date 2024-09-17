(ns monkey.ci.time-test
  (:require [clojure.test :refer [deftest testing is]]
            [java-time.api :as jt]
            [monkey.ci.time :as sut]))

(deftest date-seq
  (testing "returns lazy seq of dates from given date"
    (let [ds (sut/date-seq (jt/offset-date-time 2024 9 17))]
      (is (seq? ds))
      (is (= (jt/offset-date-time 2024 9 17) (first ds)))
      (is (= (jt/offset-date-time 2024 9 18) (second ds)))
      (is (= (jt/offset-date-time 2024 9 19) (nth ds 2))))))
