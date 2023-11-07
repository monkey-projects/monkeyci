(ns monkey.ci.test.build.shell-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [babashka.process :as bp]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [shell :as sut]]
            [monkey.ci.test.build.helpers :as h]))

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
                                  {:out (:dir opts)})}
      (let [b (sut/bash "test")]
        #(is (= "test-dir" (:output (b {:step {:work-dir "test-dir"}})))))))

  (testing "uses context checkout dir if no step dir specified"
    (with-redefs-fn {#'bp/shell (fn [opts & _]
                                  {:out (:dir opts)})}
      (let [b (sut/bash "test")]
        #(is (= "test-dir" (:output (b {:checkout-dir "test-dir"}))))))))

(deftest home
  (testing "provides home directory"
    (is (string? sut/home))))

(deftest param-to-file
  (h/with-tmp-dir dir
    (with-redefs [api/build-params (fn [_]
                                     {"test-param" "test-value"})]
      
      (testing "spits value to file"
        (let [dest (io/file dir "test-file")]
          (is (nil? (sut/param-to-file {} "test-param" dest)))
          (is (true? (.exists dest)))))

      (testing "replaces ~ with home dir"
        (with-redefs [spit (fn [f _] f)]
          (is (not (cs/starts-with? (sut/param-to-file {} "test-param" "~/test.txt")
                                    "~")))))

      (testing "writes when no dir specified"
        (let [dest (io/file "test.txt")]
          (is (nil? (sut/param-to-file {} "test-param" dest)))
          (is (true? (.exists dest)))
          (is (true? (.delete dest))))))))
