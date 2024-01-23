(ns monkey.ci.gui.test.time-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci.gui.time :as sut]))

(deftest now
  (testing "returns current time object"
    (is (some? (sut/now)))))

(deftest local-date
  (testing "returns local date"
    (is (= 2024 (-> (sut/local-date 2024 1 23)
                    (.-year))))))

(deftest format-iso
  (testing "formats datetime to iso string"
    (is (cs/starts-with? (-> (sut/local-date 2024 1 23)
                             (sut/format-iso))
                         "2024-01-23T00:00"))))

(deftest format-datetime
  (testing "formats datetime to short string"
    (is (string? (-> (sut/local-date 2024 1 23)
                     (sut/format-datetime))))))

(deftest parse-iso
  (testing "parses extended ISO datetime"
    (let [p (sut/parse-iso "2024-01-09T14:05:18.818834891Z")]
      (is (= 2024 (.-year p)))
      (is (= 1 (.-month p))))))

(deftest parse-epoch
  (testing "parses epoch millis to datetime"
    (let [p (sut/parse-epoch 1705930984443)]
      (is (= 2024 (.-year p)))
      (is (= 1 (.-month p))))))

(deftest parse
  (testing "parses `nil` to `nil`"
    (is (nil? (sut/parse nil))))

  (testing "parses epoch millis"
    (is (some? (sut/parse 1705930984443))))

  (testing "parses iso string"
    (is (some? (sut/parse "2024-01-09T14:03:00.433533291Z")))))

(deftest interval
  (testing "creates interval between two datetimes"
    (let [s (sut/local-date 2024 1 22)
          e (sut/local-date 2024 1 23)
          i (sut/interval s e)]
      (is (some? i))
      (is (pos? (.length i))))))

(deftest seconds
  (testing "calculates seconds for interval"
    (let [s (sut/parse-iso "2024-01-23T10:00:00")
          e (sut/parse-iso "2024-01-23T11:00:00")
          l (sut/seconds s e)]
      (is (= 3600 l)))))

(deftest format-interval
  (testing "formats seconds only"
    (let [s (sut/parse-iso "2024-01-23T10:00:00")
          e (sut/parse-iso "2024-01-23T10:00:10")]
      (is (= "00:00:10" (sut/format-interval s e)))))

  (testing "formats minutes and seconds"
    (let [s (sut/parse-iso "2024-01-23T10:00:00")
          e (sut/parse-iso "2024-01-23T10:02:10")]
      (is (= "00:02:10" (sut/format-interval s e)))))

    (testing "formats hours"
    (let [s (sut/parse-iso "2024-01-23T10:00:00")
          e (sut/parse-iso "2024-01-23T11:15:10")]
      (is (= "01:15:10" (sut/format-interval s e)))))

  (testing "`nil` if start or end is not provided"
    (is (nil? (sut/format-interval (sut/now) nil)))
    (is (nil? (sut/format-interval nil (sut/now))))))
