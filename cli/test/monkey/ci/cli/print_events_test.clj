(ns monkey.ci.cli.print-events-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.cli.print-events :as sut]
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
    
    (testing "sets jobs in state"
      (is (= ::test-jobs
             (-> {:event
                  {:jobs ::test-jobs}}
                 (enter)
                 (emi/get-state)
                 (sut/get-jobs)))))))
