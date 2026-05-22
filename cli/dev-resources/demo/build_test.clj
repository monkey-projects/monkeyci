(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [build :as sut]))

(deftest jobs
  (testing "contains test job"
    (is (= 1 (count sut/jobs)))))
