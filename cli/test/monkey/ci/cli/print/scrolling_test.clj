(ns monkey.ci.cli.print.scrolling-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.cli.print.scrolling :as sut]
            [monkey.ci.events.mailman.interceptors :as emi]))

(deftest make-routes
  (testing "handles required event types"
    (is (not-empty (->> (sut/make-routes {})
                        (map first))))))
(deftest save-local-dir
  (let [{:keys [enter] :as i} sut/save-local-dir]
    (is (keyword? (:name i)))
    
    (testing "sets local dir for job in state"
      (let [ctx (-> {:event
                  {:job-id "test-job" :local-dir "/test/dir"}}
                 (emi/set-state (sut/set-jobs {} {"test-job" {}}))
                 (enter))]
        (is (= "/test/dir"
               (-> ctx
                   (emi/get-state)
                   (sut/get-local-dir "test-job"))))
        (is (= {:local-dir "/test/dir"}
               (sut/get-job ctx "test-job")))))))

(deftest save-jobs
  (let [{:keys [enter] :as i} sut/save-jobs]
    (is (keyword? (:name i)))
    
    (testing "sets jobs in state, grouped by id"
      (is (= {::test-job {:id ::test-job}}
             (-> {:event
                  {:jobs [{:id ::test-job}]}}
                 (enter)
                 (emi/get-state)
                 (sut/get-jobs)))))))

(deftest get-job
  (testing "retrieves job by id from ctx"
    (let [job {:id "test-job"}]
      (is (= job
             (-> {:event
                  {:job-id (:id job)}}
                 (emi/set-state (sut/set-jobs {} {(:id job) job}))
                 (sut/get-job)))))))

(deftest get-cmd
  (testing "retrieves script cmd by idx"
    (let [job {:id "test-job"
               :script ["first cmd"
                        "second cmd"]}]
      (is (= "second cmd"
             (-> {:event
                  {:job-id (:id job)}}
                 (emi/set-state (sut/set-jobs {} {(:id job) job}))
                 (sut/get-cmd 1)))))))

(deftest pad-job-id
  (testing "returns job id when no other jobs"
    (is (= "test-job"
           (-> {}
               (emi/set-state (sut/set-jobs {} {"test-job" {}}))
               (sut/pad-job-id "test-job")))))

  (testing "returns default job id"
    (is (= "test-job"
           (-> {:event
                {:job-id "test-job"}}
               (emi/set-state (sut/set-jobs {} {"test-job" {}}))
               (sut/pad-job-id)))))

  (testing "pads right to max length"
    (is (= "short    "
           (-> {}
               (emi/set-state 
                (sut/set-jobs {}
                              {"short" {}
                               "very long" {}}))
               (sut/pad-job-id "short"))))))
