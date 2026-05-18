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
    
    (testing "sets local dir in state"
      (is (= "/test/dir"
             (-> {:event
                  {:local-dir "/test/dir"}}
                 (enter)
                 (emi/get-state)
                 (sut/get-local-dir)))))))

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
