(ns monkey.ci.artifacts-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.string :as cs]
            [monkey.ci
             [artifacts :as sut]
             [blob :as blob]]
            [monkey.ci.helpers :as h]))

(deftest save-artifacts
  (testing "saves path using blob store, relative to job work dir"
    (h/with-tmp-dir dir
      (let [p (doto (fs/path dir "test-path")
                (fs/create-file))
            stored (atom {})
            bs (h/fake-blob-store stored)
            ctx {:artifacts bs
                 :build {:sid ["test-cust" "test-build"]}
                 :job {:work-dir dir
                       :save-artifacts [{:id "test-artifact"
                                         :path "test-path"}]}}]
        (is (some? @(sut/save-artifacts ctx)))
        (is (= 1 (count @stored)))
        (let [[p dest] (first @stored)]
          (is (cs/ends-with? dest "test-cust/test-build/test-artifact.tgz"))
          (is (= (str dir "/test-path") p))))))

  (testing "nothing if no cache store"
    (is (empty? @(sut/save-artifacts
                  {:job {:save-artifacts [{:id "test-artifact"
                                           :path "test-path"}]}})))))

(deftest restore-artifacts
  (testing "restores path using blob store"
    (let [stored (atom {"test-cust/test-build/test-artifact.tgz" ::dest})
          bs (h/fake-blob-store stored)
          ctx {:artifacts bs
               :build {:sid ["test-cust" "test-build"]}
               :job {:work-dir "work"
                     :restore-artifacts [{:id "test-artifact"
                                          :path "test-path"}]}}]
      (is (some? @(sut/restore-artifacts ctx)))
      (is (empty? @stored) "expected entry to be restored"))))
