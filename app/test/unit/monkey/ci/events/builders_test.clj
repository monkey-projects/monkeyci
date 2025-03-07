(ns monkey.ci.events.builders-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.builders :as sut]))

(deftest job-executed-evt
  (testing "adds status from result"
    (is (= :success
           (-> (sut/job-executed-evt "test-job" ["test-build"] {:status :success})
               :status))))

  (testing "adds result"
    (is (= {:output "test result"}
           (-> (sut/job-executed-evt "test-job" ["test-build"] {:status :success
                                                                :output "test result"})
               :result)))))
