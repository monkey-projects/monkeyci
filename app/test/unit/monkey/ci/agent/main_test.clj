(ns monkey.ci.agent.main-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.agent.main :as sut]))

(deftest main
  (testing "starts agent runtime"
    (is (nil? (sut/-main {})))))
