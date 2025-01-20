(ns monkey.ci.time-test
  (:require [clojure.test :refer [deftest testing is]]
            [java-time.api :as jt]
            [monkey.ci.time :as sut]))

(deftest day-start
  (testing "returns offset date at midnight"
    (is (= (jt/offset-date-time 2025 1 20)
           (sut/day-start (jt/offset-date-time 2025 1 20 10))))))

(deftest date-seq
  (testing "returns lazy seq of dates from given date"
    (let [ds (sut/date-seq (jt/offset-date-time 2024 9 17))]
      (is (seq? ds))
      (is (= (jt/offset-date-time 2024 9 17) (first ds)))
      (is (= (jt/offset-date-time 2024 9 18) (second ds)))
      (is (= (jt/offset-date-time 2024 9 19) (nth ds 2))))))

(defn- date->ts [y m d h]
  (-> (jt/zoned-date-time (jt/local-date y m d) (jt/local-time h) sut/utc-zone)
      (jt/instant sut/utc-zone)
      (jt/to-millis-from-epoch)))

(deftest same-date?
  (testing "true if the epoch millis are on the same UTC date"
    (is (sut/same-date? (date->ts 2025 1 15 10)
                        (date->ts 2025 1 15 11)))
    (is (not (sut/same-date? (date->ts 2025 1 15 10)
                             (date->ts 2025 1 16 10))))))

(deftest same-dom?
  (testing "true if the epoch millis are on the same UTC day of month"
    (is (sut/same-dom? (date->ts 2025 1 15 10)
                       (date->ts 2025 2 15 10)))
    (is (not (sut/same-dom? (date->ts 2025 1 15 10)
                            (date->ts 2025 1 16 10))))))
