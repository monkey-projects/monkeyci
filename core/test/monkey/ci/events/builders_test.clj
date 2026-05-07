(ns monkey.ci.events.builders-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.builders :as sut]))

(defn ->edn [x]
  (pr-str x))

(defn edn-> [s]
  (edn/read-string s))

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
                (->edn)
                (edn->))]
      (is (map? r))
      (is (= "test-job" (:id r)))))

  (testing "makes container job serializable"
    (let [r (-> (bc/container-job "test-job" {:image "test-img"})
                (sut/job->event)
                (->edn)
                (edn->))]
      (is (map? r))
      (is (= "test-job" (:id r)))
      (is (= "test-img" (:image r)))))

  (testing "drops caches extra props"
    (is (= [{:id "test-cache"
             :path "test/cache"}]
           (-> (bc/container-job "test-job" {})
               (assoc :caches [{:id "test-cache"
                                :path "test/cache"
                                :other :prop}])
               (sut/job->event)
               :caches)))))

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
