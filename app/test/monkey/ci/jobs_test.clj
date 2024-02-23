(ns monkey.ci.jobs-test
  (:require [monkey.ci.jobs :as sut]
            [clojure.test :refer [deftest testing is]]))

#_(deftest make-job-graph
  (testing "ok"
    (is (= 1 1)))
  #_(testing "empty for empty set"
    (is (= [] (sut/make-job-graph []))))

  #_(testing "roots contain all jobs without dependencies"
    (let [[a b :as jobs] [{:id ::first}
                          {:id ::second}]]
      (is (= [[a] [b]]
             (sut/make-job-graph jobs)))))

  #_(testing "roots point to dependent jobs"
    (let [[p c :as jobs] [{:id ::root}
                          {:id ::child
                           :dependencies [::root]}]]
      (is (= [[p [[c]]]]
             (sut/make-job-graph jobs))))))

(deftest next-jobs
  (testing "returns starting jobs if all are pending"
    (let [[a :as jobs] [(sut/job ::root)
                        (sut/job ::child {:dependencies [::root]})]]
      (is (= [a] (sut/next-jobs jobs)))))

  (testing "returns jobs that have succesful dependencies"
    (let [[_ b :as jobs] [(sut/job ::root {:status :success})
                          (sut/job ::child {:dependencies [::root]})]]
      (is (= [b] (sut/next-jobs jobs)))))

  (testing "does not return jobs that have failed dependencies"
    (let [[_ _ c :as jobs] [(sut/job ::root {:status :success})
                          (sut/job ::other-root {:status :failure})
                          (sut/job ::child {:dependencies [::root]})
                          (sut/job ::other-child {:dependencies [::other-root]})]]
      (is (= [c] (sut/next-jobs jobs)))))

  (testing "returns jobs that have multiple succesful dependencies"
    (let [[_ _ c :as jobs] [(sut/job ::root {:status :success})
                            (sut/job ::other-root {:status :success})
                            (sut/job ::child {:dependencies [::root ::other-root]})]]
      (is (= [c] (sut/next-jobs jobs))))))
