(ns monkey.ci.sid-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.sid :as sut]))

(deftest parse-sid
  (testing "parses string"
    (is (= ["a" "b"] (sut/parse-sid "a/b"))))

  (testing "leaves seq as-is"
    (is (= ["a" "b"] (sut/parse-sid ["a" "b"])))))
