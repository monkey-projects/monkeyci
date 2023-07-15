(ns monkey.ci.test.utils-test
  (:require [clojure.test :refer :all]
            [monkey.ci.utils :as sut]))

(deftest abs-path
  (testing "returns abs path as is"
    (is (= "/abs" (sut/abs-path "/parent" "/abs"))))

  (testing "returns child if nil parent"
    (is (= "child" (sut/abs-path nil "child"))))

  (testing "returns subpath of parent if child is not absolute"
    (is (= "parent/child" (sut/abs-path "parent" "child")))))
