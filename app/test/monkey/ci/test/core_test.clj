(ns monkey.ci.test.core-test
  (:require [clojure.test :refer :all]
            [monkey.ci.core :as sut]))

(deftest ^:integration main-test
  (with-redefs [clojure.core/shutdown-agents (constantly nil)]
    (testing "main returns nil"
      (is (nil? (sut/-main))))
    
    (testing "runs script at location"
      (is (zero? (sut/-main "examples/basic-clj"))))))
