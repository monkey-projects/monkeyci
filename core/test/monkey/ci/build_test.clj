(ns monkey.ci.build-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [build :as sut]
             [runtime :as rt]
             [utils :as u]]))

(deftest calc-checkout-dir
  (testing "combines build id with checkout base dir from config"
    (let [rt {:config {:checkout-base-dir "/tmp/checkout-base"}}
          build {:build-id "test-build"}]
      (is (= "/tmp/checkout-base" ((rt/from-config :checkout-base-dir) rt)))
      (is (= "/tmp/checkout-base/test-build"
             (sut/calc-checkout-dir rt build))))))

(deftest job-work-dir
  (testing "returns job work dir as absolute path"
    (is (= "/job/work/dir"
           (sut/job-work-dir {:work-dir "/job/work/dir"}
                             {}))))

  (testing "returns checkout dir when no job dir given"
    (is (= "/checkout/dir"
           (sut/job-work-dir {}
                             "/checkout/dir"))))

  (testing "returns current working dir when no job or checkout dir given"
    (is (= (u/cwd)
           (sut/job-work-dir {} nil))))

  (testing "returns work dir as relative dir of checkout dir"
    (is (= "/checkout/job-dir"
           (sut/job-work-dir {:work-dir "job-dir"}
                             "/checkout")))))

(deftest build-end-evt
  (testing "creates build/end event with status completed"
    (let [build {:build-id "test-build"}
          evt (sut/build-end-evt build 0)]
      (is (= :build/end (:type evt)))
      (is (= "test-build" (get-in evt [:build :build-id])))))

  (testing "sets status according to exit"
    (is (= :success
           (-> (sut/build-end-evt {} 0)
               (get-in [:build :status]))))
    (is (= :error
           (-> (sut/build-end-evt {} 1)
               (get-in [:build :status])))))

  (testing "error status when no exit"
    (is (= :error (-> (sut/build-end-evt {} nil)
                      (get-in [:build :status]))))))

(deftest calc-credits
  (testing "zero if no jobs"
    (is (zero? (sut/calc-credits {}))))

  (testing "sum of consumed credits for all jobs"
    (let [build {:script
                 {:jobs
                  {:job-1 {:id "job-1"
                           :start-time 0
                           :end-time 60000
                           :credit-multiplier 3}
                   :job-2 {:id "job-2"
                           :start-time 0
                           :end-time 120000
                           :credit-multiplier 4}}}}]
      (is (= 11 (sut/calc-credits build)))))

  (testing "rounds up"
    (let [build {:script
                 {:jobs
                  {:job-1 {:id "job-1"
                           :start-time 0
                           :end-time 10000
                           :credit-multiplier 3}
                   :job-2 {:id "job-2"
                           :start-time 0
                           :end-time 20000
                           :credit-multiplier 4}}}}]
      (is (= 2 (sut/calc-credits build))))))

(deftest sid
  (testing "returns `:sid` from build"
    (is (= ::test-sid (sut/sid {:sid ::test-sid}))))

  (testing "when no `sid`, returns customer, repo and build id"
    (is (= [::test-cust ::test-repo ::test-build]
           (sut/sid {:org-id ::test-cust
                     :repo-id ::test-repo
                     :build-id ::test-build})))))

(deftest org-id
  (testing "`:org-id` from build"
    (is (= "test-org" (sut/org-id {:org-id "test-org"}))))

  (testing "first from sid"
    (is (= "test-org" (sut/org-id {:sid ["test-org"]})))))
