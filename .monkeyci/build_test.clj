(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [build :as sut]))

(deftest tag-version
  (testing "returns valid version"
    (is (= "0.1.0"
           (sut/tag-version {:build
                             {:git
                              {:ref "refs/tags/0.1.0"}}}))))

  (testing "`nil` if not a version number"
    (is (nil?
         (sut/tag-version {:build
                           {:git
                            {:ref "refs/tags/other"}}})))))
