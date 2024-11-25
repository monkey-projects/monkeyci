(ns monkey.ci.build.v2-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build.v2 :as sut]))

(def test-ctx {})

(deftest action-job
  (testing "creates action job"
    (is (sut/action-job? (sut/action-job "test-job" (constantly "ok"))))))

(deftest container-job
  (testing "creates container job"
    (is (sut/container-job? (sut/container-job "test-job" {:image "test-img"})))))

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
             (-> (job test-ctx)
                 (sut/dependencies)))))))

(deftest image
  (testing "gets container job image"
    (is (= "test-img"
           (-> (sut/container-job "test-job" {:container/image "test-img"})
               (sut/image)))))

  (testing "sets container job image"
    (is (= "test-img"
           (-> (sut/container-job "test-job")
               (sut/image "test-img")
               (sut/image)))))

  (testing "sets container job fn image"
    (let [job (-> (fn [_] (sut/container-job "test-job"))
                  (sut/image "test-img"))]
      (is (= "test-img"
             (sut/image (job test-ctx)))))))
