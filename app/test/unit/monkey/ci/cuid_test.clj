(ns monkey.ci.cuid-test
  (:require [monkey.ci.cuid :as sut]
            [clojure.test :refer [deftest testing is]]))

(deftest random-cuid
  (testing "generates random 24-char string"
    (let [cuid (sut/random-cuid)]
      (is (string? cuid))
      (is (= sut/cuid-length (count cuid)))
      (is (sut/cuid? cuid)))))

(deftest cuid?
  (testing "recognizes a valid cuid"
    (is (not (sut/cuid? nil)))
    (is (sut/cuid? (sut/random-cuid)))
    (is (not (sut/cuid? "abc")))))
