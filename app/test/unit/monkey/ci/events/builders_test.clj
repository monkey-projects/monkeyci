(ns monkey.ci.events.builders-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.api :as api]
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
    (let [r (-> (api/action-job "test-job" (constantly nil))
                (sut/job->event)
                (edn/->edn)
                (edn/edn->))]
      (is (map? r))
      (is (= "test-job" (:id r)))))

  (testing "makes container job serializable"
    (let [r (-> (api/container-job "test-job" {:image "test-img"})
                (sut/job->event)
                (edn/->edn)
                (edn/edn->))]
      (is (map? r))
      (is (= "test-job" (:id r)))
      (is (= "test-img" (:image r)))))

  (testing "drops caches extra props"
    (is (= [{:id "test-cache"
             :path "test/cache"}]
           (-> (api/container-job "test-job" {})
               (api/caches [{:id "test-cache"
                             :path "test/cache"
                             :other :prop}])
               (sut/job->event)
               :caches)))))
