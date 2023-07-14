(ns monkey.ci.test.process-test
  (:require [clojure.test :refer :all]
            [monkey.ci.process :as sut]))

(deftest ^:slow execute!
  (testing "executes build script in separate process"
    (is (zero? (:exit (sut/execute! {:dev-mode true
                                     :work-dir "examples/basic-clj"})))))

  (testing "fails when script fails"
    (is (pos? (:exit (sut/execute! {:dev-mode true
                                    :work-dir "examples/failing"})))))

  (testing "throws when script not found"
    (is (thrown? java.io.IOException (sut/execute! {:dev-mode true
                                                    :work-dir "examples/non-existing"})))))
