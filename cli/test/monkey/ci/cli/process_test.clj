(ns monkey.ci.cli.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.cli.process :as sut]))

(deftest run-test
  (testing "returns the exit code of the process"
    (is (zero? (sut/run ["echo" "hello"] "."))))

  (testing "returns non-zero exit code on failure"
    (is (not (zero? (sut/run ["sh" "-c" "exit 1"] ".")))))

  (testing "runs the process in the specified directory"
    (let [tmp (System/getProperty "java.io.tmpdir")]
      (is (zero? (sut/run ["sh" "-c" "test -d ."] tmp))))))
