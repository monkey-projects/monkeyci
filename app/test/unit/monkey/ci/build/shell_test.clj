(ns monkey.ci.build.shell-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [babashka.process :as bp]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [shell :as sut]]
            [monkey.ci.build.helpers :as h]
            [monkey.ci.jobs :as j]))

(deftest bash
  (testing "returns fn"
    (is (fn? (sut/bash "test"))))

  (testing "is a job fn"
    (is (j/job-fn? (sut/bash "test"))))
  
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
        #(is (= "test-dir" (:output (b {:job {:work-dir "test-dir"}})))))))

  (testing "uses context checkout dir if no job dir specified"
    (with-redefs-fn {#'bp/shell (fn [opts & _]
                                  {:out (:dir opts)})}
      (let [b (sut/bash "test")]
        #(is (= "test-dir" (:output (b {:checkout-dir "test-dir"})))))))

  (testing "adds env vars as extra envs"
    (with-redefs-fn {#'bp/shell (fn [opts & _]
                                  {:out (:extra-env opts)})}
      (let [b (sut/bash {:env {"key" "value"}} "test")]
        #(is (= {"key" "value"} (:output (b {})))))))

  (testing "uses env vars from job"
    (with-redefs-fn {#'bp/shell (fn [opts & _]
                                  {:out (:extra-env opts)})}
      (let [b (core/action-job "test-job" (sut/bash "test") {:env {"key" "value"}})]
        #(is (= {"key" "value"} (-> (j/execute! b {:job b})
                                    (deref)
                                    :output)))))))

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

(deftest in-work
  (testing "returns relative to job work dir"
    (is (= "/script/sub"
           (sut/in-work {:job {:work-dir "/script"}}
                        "sub"))))

  (testing "fails with absolute path"
    (is (thrown? Exception
                 (sut/in-work {:job {:work-dir "/script"}}
                              "/abs")))))
