(ns monkey.ci.script-test
  (:require
   [clojure.spec.alpha :as spec]
   [clojure.test :refer :all]
   [manifold.bus :as mb]
   [manifold.deferred :as md]
   [monkey.ci.build :as b]
   [monkey.ci.build.core :as bc]
   [monkey.ci.events.core :as ec]
   [monkey.ci.jobs :as j]
   [monkey.ci.runtime :as rt]
   [monkey.ci.script :as sut]
   [monkey.ci.spec.events :as se]
   [monkey.ci.test.aleph-test :as at]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.utils :as u]))

(defn dummy-job
  ([r]
   (bc/action-job ::test-job (constantly r)))
  ([]
   (dummy-job bc/success)))

(deftest resolve-jobs
  (testing "invokes fn"
    (let [job (dummy-job)
          p (bc/pipeline {:jobs [job]})]
      (is (= [job] (sut/resolve-jobs (constantly p) {})))))

  (testing "auto-assigns ids to jobs"
    (let [jobs (repeat 10 (bc/action-job nil (constantly ::test)))
          p (sut/resolve-jobs (vec jobs) {})]
      (is (not-empty p))
      (is (every? :id p))
      (is (= (count jobs) (count (distinct (map :id p)))))))

  (testing "assigns id as metadata to function"
    (let [p (sut/resolve-jobs [(bc/action-job nil (constantly ::ok))] {})]
      (is (= 1 (count p)))
      (is (= "job-1" (-> p
                         first
                         bc/job-id)))))

  (testing "does not overwrite existing id"
    (is (= ::test-id (-> {:jobs [{:id ::test-id
                                  :action (constantly :ok)}]}
                         (bc/pipeline)
                         (sut/resolve-jobs {})
                         first
                         bc/job-id))))

  (testing "returns jobs as-is"
    (let [jobs (repeatedly 10 dummy-job)]
      (is (= jobs (sut/resolve-jobs jobs {})))))

  (testing "resolves job resolvables"
    (let [job (dummy-job)]
      (is (= [job] (sut/resolve-jobs [(constantly job)] {}))))))

