(ns monkey.ci.test.process-test
  (:require [clojure.test :refer :all]
            [monkey.ci.process :as sut]))

(deftest ^:slow execute!
  (testing "executes build script in separate process"
    (is (zero? (:exit (sut/execute! {:dev-mode true
                                     :script-dir "examples/basic-clj"})))))

  (testing "fails when script fails"
    (is (pos? (:exit (sut/execute! {:dev-mode true
                                    :script-dir "examples/failing"})))))

  (testing "fails when script not found"
    (is (pos? (:exit (sut/execute! {:dev-mode true
                                    :script-dir "examples/non-existing"}))))))
