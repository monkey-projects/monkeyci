(ns monkey.ci.k8s-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.k8s :as sut]))

(deftest parse-mem
  (testing "parses K to gb"
    (is (= 1 (sut/parse-mem "1000000K"))))

  (testing "parses Mi to gb"
    (is (= 1 (sut/parse-mem "1024Mi")))))
