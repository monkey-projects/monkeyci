(ns monkey.ci.jobs-test
  (:require [monkey.ci.jobs :as sut]
            [clojure.test :refer [deftest testing is]]))

(defn dummy-job [id & [opts]]
  (sut/action-job id (constantly nil) opts))

(deftest next-jobs
  (testing "returns starting jobs if all are pending"
    (let [[a :as jobs] [(dummy-job ::root)
                        (dummy-job ::child {:dependencies [::root]})]]
      (is (= [a] (sut/next-jobs jobs)))))

  (testing "returns jobs that have succesful dependencies"
    (let [[_ b :as jobs] [(dummy-job ::root {:status :success})
                          (dummy-job ::child {:dependencies [::root]})]]
      (is (= [b] (sut/next-jobs jobs)))))

  (testing "does not return jobs that have failed dependencies"
    (let [[_ _ c :as jobs] [(dummy-job ::root {:status :success})
                            (dummy-job ::other-root {:status :failure})
                            (dummy-job ::child {:dependencies [::root]})
                            (dummy-job ::other-child {:dependencies [::other-root]})]]
      (is (= [c] (sut/next-jobs jobs)))))

  (testing "returns jobs that have multiple succesful dependencies"
    (let [[_ _ c :as jobs] [(dummy-job ::root {:status :success})
                            (dummy-job ::other-root {:status :success})
                            (dummy-job ::child {:dependencies [::root ::other-root]})]]
      (is (= [c] (sut/next-jobs jobs))))))

(def test-job (dummy-job ::recursive-job))

(deftest resolve-job
  (testing "returns job"
    (let [job (dummy-job ::test-job)]
      (is (= job (sut/resolve-job job {})))))

  (testing "invokes fn to return job"
    (let [job (dummy-job ::indirect-job)]
      (is (= job (sut/resolve-job (constantly job) {})))))

  (testing "recurses into job"
    (let [job (dummy-job ::recursive-job)]
      (is (= job (sut/resolve-job (constantly (constantly job)) {})))))

  (testing "resolves var"
    (is (= test-job (sut/resolve-job #'test-job {}))))

  (testing "resolves `nil`"
    (is (nil? (sut/resolve-job nil {})))))

(deftest action-job
  (let [job (sut/action-job ::test-job (constantly ::result))]
    (testing "is a job"
      (is (sut/job? job)))
    
    (testing "executes action"
      (is (= ::result (sut/execute! job {}))))))

(deftest execute-jobs!
  (testing "empty if no jobs"
    (is (empty? @(sut/execute-jobs! [] {})))))
