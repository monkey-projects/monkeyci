(ns monkey.ci.build.v2-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.process :as bp]
            [monkey.ci.build.v2 :as sut]
            [monkey.ci.jobs :as j]))

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

(deftest script
  (testing "sets and gets script from container job"
    (is (= ["first" "second"]
           (-> (sut/container-job "test-job")
               (sut/script ["first" "second"])
               (sut/script))))))

(deftest env
  (testing "gets container job env"
    (is (= {"key" "value"}
           (-> (sut/container-job "test-job" {:container/env {"key" "value"}})
               (sut/env)))))

  (testing "gets action job env"
    (is (= {"key" "value"}
           (-> (sut/action-job "test-job" (constantly "ok") {:env {"key" "value"}})
               (sut/env)))))

  (testing "sets action job env"
    (is (= {"key" "value"}
           (-> (sut/action-job "test-job" (constantly "ok"))
               (sut/env {"key" "value"})
               (sut/env)))))

  (testing "sets container job env"
    (is (= {"key" "value"}
           (-> (sut/container-job "test-job")
               (sut/env {"key" "value"})
               (sut/env)))))

  (testing "sets fn job env"
    (let [job (-> (constantly (sut/action-job "fn-job" (constantly "ok")))
                  (sut/env {"key" "value"}))]
      (is (= {"key" "value"}
             (sut/env (job test-ctx)))))))

(deftest work-dir
  (testing "gets container job work-dir"
    (is (= "/test/dir"
           (-> (sut/container-job "test-job" {:work-dir "/test/dir"})
               (sut/work-dir)))))

  (testing "gets action job work-dir"
    (is (= "/work/dir"
           (-> (sut/action-job "test-job" (constantly "ok") {:work-dir "/work/dir"})
               (sut/work-dir)))))

  (testing "sets action job work-dir"
    (is (= "/work/dir"
           (-> (sut/action-job "test-job" (constantly "ok"))
               (sut/work-dir "/work/dir")
               (sut/work-dir)))))

  (testing "sets container job work-dir"
    (is (= "/work/dir"
           (-> (sut/container-job "test-job")
               (sut/work-dir "/work/dir")
               (sut/work-dir)))))

  (testing "sets fn job work-dir"
    (let [job (-> (constantly (sut/action-job "fn-job" (constantly "ok")))
                  (sut/work-dir "/work/dir"))]
      (is (= "/work/dir"
             (sut/work-dir (job test-ctx)))))))

(deftest save-artifacts
  (let [art (sut/artifact "test-artifact" "/test/path")]
    (testing "sets save-artifacts on job"
      (is (= [art] (-> (sut/action-job "test-job" (constantly ::ok))
                       (sut/save-artifacts art)
                       :save-artifacts))))

    (testing "adds to existing artifacts"
      (let [orig (sut/artifact "original" "/tmp")]
        (is (= [orig art]
               (-> (sut/action-job "test-job" (constantly ::ok) {:save-artifacts [orig]})
                   (sut/save-artifacts art)
                   :save-artifacts)))))

    (testing "sets save-artifacts on job fn"
      (let [job (-> (constantly (sut/container-job "test-job"))
                    (sut/save-artifacts art))]
        (is (= [art] (:save-artifacts (job test-ctx))))))))

(deftest restore-artifacts
  (let [art (sut/artifact "test-artifact" "/test/path")]
    (testing "sets restore-artifacts on job"
      (is (= [art] (-> (sut/action-job "test-job" (constantly ::ok))
                       (sut/restore-artifacts art)
                       :restore-artifacts))))

    (testing "adds to existing artifacts"
      (let [orig (sut/artifact "original" "/tmp")]
        (is (= [orig art]
               (-> (sut/action-job "test-job" (constantly ::ok) {:restore-artifacts [orig]})
                   (sut/restore-artifacts art)
                   :restore-artifacts)))))

    (testing "sets restore-artifacts on job fn"
      (let [job (-> (constantly (sut/container-job "test-job"))
                    (sut/restore-artifacts art))]
        (is (= [art] (:restore-artifacts (job test-ctx))))))))

(deftest caches
  (let [art (sut/cache "test-artifact" "/test/path")]
    (testing "sets caches on job"
      (is (= [art] (-> (sut/action-job "test-job" (constantly ::ok))
                       (sut/caches art)
                       :caches))))

    (testing "adds to existing artifacts"
      (let [orig (sut/cache "original" "/tmp")]
        (is (= [orig art]
               (-> (sut/action-job "test-job" (constantly ::ok) {:caches [orig]})
                   (sut/caches art)
                   :caches)))))

    (testing "sets caches on job fn"
      (let [job (-> (constantly (sut/container-job "test-job"))
                    (sut/caches art))]
        (is (= [art] (:caches (job test-ctx))))))))

(deftest file-changes
  (testing "`added?`"
    (testing "checks if file has been added"
      (let [ctx {:build
                 {:changes
                  {:added ["a" "b"]}}}]
        (is (true? (sut/added? ctx "a")))
        (is (true? ((sut/added? "a") ctx)))
        (is (false? (sut/added? ctx #"c")))
        (is (false? ((sut/added? #"c") ctx))))))

  (testing "`removed?`"
    (testing "checks if file has been removed"
      (let [ctx {:build
                 {:changes
                  {:removed ["a" "b"]}}}]
        (is (true? (sut/removed? ctx "a")))
        (is (true? ((sut/removed? "a") ctx)))
        (is (false? (sut/removed? ctx #"c")))
        (is (false? ((sut/removed? #"c") ctx))))))

  (testing "`modified?`"
    (testing "checks if file has been modified"
      (let [ctx {:build
                 {:changes
                  {:modified ["a" "b"]}}}]
        (is (true? (sut/modified? ctx "a")))
        (is (true? ((sut/modified? "a") ctx)))
        (is (false? (sut/modified? ctx #"c")))
        (is (false? ((sut/modified? #"c") ctx)))))))

(deftest bash
  (testing "creates action job that executes bash script"
    (let [job (sut/bash "test-job" "ls -l")]
      (with-redefs [bp/shell (fn [& args]
                               {:out args})]
        (is (sut/action-job? job))
        (is (= "test-job" (sut/job-id job)))
        (is (= "ls -l" (last (:output @(j/execute! job {})))))))))
