(ns monkey.ci.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.jobs :as sut]))

(def ^:private example-job
  {:id "test-job" :type :container :status :pending :size 2 :memory 4})

(deftest job-id-test
  (testing "returns :id"
    (is (= "test-job" (sut/job-id example-job)))))

(deftest status-predicates
  (testing "pending?"
    (is (sut/pending? {:status :pending}))
    (is (sut/pending? {}))
    (is (not (sut/pending? {:status :running}))))

  (testing "running?"
    (is (sut/running? {:status :running}))
    (is (not (sut/running? {:status :pending}))))

  (testing "failed?"
    (is (sut/failed? {:status :failure}))
    (is (sut/failed? {:status :error}))
    (is (not (sut/failed? {:status :success}))))

  (testing "success?"
    (is (sut/success? {:status :success}))
    (is (not (sut/success? {:status :failure}))))

  (testing "active?"
    (is (sut/active? {:status :running}))
    (is (sut/active? {:status :queued}))
    (is (sut/active? {:status :initializing}))
    (is (not (sut/active? {:status :pending}))))

  (testing "blocked?"
    (is (sut/blocked? {:status :blocked}))
    (is (not (sut/blocked? {:status :pending})))))

(deftest size->cpus-test
  (testing "uses :size when present"
    (is (= 2 (sut/size->cpus {:size 2}))))
  (testing "uses :cpus as fallback"
    (is (= 4 (sut/size->cpus {:cpus 4}))))
  (testing "defaults to 1"
    (is (= 1 (sut/size->cpus {})))))

(deftest size->mem-test
  (testing "doubles :size"
    (is (= 4 (sut/size->mem {:size 2}))))
  (testing "uses :memory as fallback"
    (is (= 8 (sut/size->mem {:memory 8}))))
  (testing "defaults to 2"
    (is (= 2 (sut/size->mem {})))))

(deftest timeouts
  (testing "default-job-timeout is positive"
    (is (pos? sut/default-job-timeout)))
  (testing "max-job-timeout is larger than default"
    (is (< sut/default-job-timeout sut/max-job-timeout))))
