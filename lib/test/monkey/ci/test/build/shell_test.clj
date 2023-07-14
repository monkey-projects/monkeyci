(ns monkey.ci.test.build.shell-test
  (:require [clojure.test :refer :all]
            [babashka.process :as bp]
            [monkey.ci.build
             [core :as core]
             [shell :as sut]]))

(deftest bash
  (testing "returns fn"
    (is (fn? (sut/bash "test"))))
  
  (testing "returns success"
    (with-redefs-fn {#'bp/shell (constantly {})}
      (let [b (sut/bash "test")]
        #(is (core/success? (b {}))))))
  
  (testing "adds output to result"
    (with-redefs-fn {#'bp/shell (constantly {:out "test-output"})}
      (let [b (sut/bash "test")]
        #(is (= "test-output" (:output (b {})))))))

  (testing "adds error in case of failure"
    (with-redefs-fn {#'bp/shell (fn [& _]
                                  (throw (ex-info "test error" {})))}
      (let [b (sut/bash "test")]
        #(is (some? (:exception (b {})))))))

  (testing "handles work dir"
    (with-redefs-fn {#'bp/shell (fn [opts & _]
                                  {:out (:work-dir opts)})}
      (let [b (sut/bash "test")]
        #(is (= "test-dir" (:output (b {:step {:work-dir "test-dir"}}))))))))

