(ns monkey.ci.utils.path-test
  (:require [monkey.ci.utils.path :as sut]
            [clojure.test :refer [deftest testing is]]))

(deftest abs-path
  (testing "returns abs path as is"
    (is (= "/abs" (sut/abs-path "/parent" "/abs"))))

  (testing "returns child if nil parent"
    (is (= "child" (sut/abs-path nil "child"))))

  (testing "returns subpath of parent if child is not absolute"
    (is (= "/parent/child" (sut/abs-path "/parent" "child")))))
