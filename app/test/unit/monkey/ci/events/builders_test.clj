(ns monkey.ci.events.builders-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.edn :as edn]
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

(deftest job->event
  (testing "makes action job serializable"
    (let [r (-> (bc/action-job "test-job" (constantly nil))
                (sut/job->event)
                (edn/->edn)
                (edn/edn->))]
      (is (map? r))
      (is (= "test-job" (:id r)))))

  (testing "makes container job serializable"
    (let [r (-> (bc/container-job "test-job" {:image "test-img"})
                (sut/job->event)
                (edn/->edn)
                (edn/edn->))]
      (is (map? r))
      (is (= "test-job" (:id r)))
      (is (= "test-img" (:image r))))))
