(ns monkey.ci.build.v2-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build.v2 :as sut]))

(deftest action-job
  (testing "creates action job"
    (is (sut/action-job? (sut/action-job "test-job" (constantly "ok"))))))

(deftest depends-on
  (testing "sets dependencies on action job"
    (is (= ["other-job"] (-> (sut/action-job "test-job" (constantly "ok"))
                             (sut/depends-on ["other-job"])
                             (sut/dependencies)))))

  (testing "accepts single dep"
    (is (= ["other-job"] (-> (sut/action-job "test-job" (constantly "ok"))
                             (sut/depends-on "other-job")
                             (sut/dependencies)))))

  (testing "accepts varargs"
    (is (= ["other-job" "yet-another-job"]
           (-> (sut/action-job "test-job" (constantly "ok"))
               (sut/depends-on "other-job" "yet-another-job")
               (sut/dependencies)))))

  (testing "adds dependencies to existing job"
    (is (= ["a" "b"]
           (-> (sut/action-job "test-job" (constantly "ok") {:dependencies ["a"]})
               (sut/depends-on ["b"])
               (sut/dependencies)))))

  (testing "drops duplicates"
    (is (= ["a"]
           (-> (sut/action-job "test-job" (constantly "ok") {:dependencies ["a"]})
                             (sut/depends-on ["a"])
                             (sut/dependencies)))))

  (testing "adds dependencies for functions"
    (let [job (-> (fn [_]
                    (sut/action-job "test-job" (constantly "ok")))
                  (sut/depends-on ["other-job"]))]
      (is (= ["other-job"]
             (-> (job {})
                 (sut/dependencies)))))))
