(ns monkey.ci.cache-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci
             [blob :as blob]
             [cache :as sut]]
            [monkey.ci.helpers :as h]))

(deftest save-caches
  (testing "saves path using blob store"
    (let [stored (atom {})
          bs (h/fake-blob-store stored)
          ctx {:cache bs
               :build {:sid ["test-cust" "test-build"]}
               :job {:work-dir "work"
                     :caches [{:id "test-cache"
                               :path "test-path"}]}}]
      (is (some? @(sut/save-caches ctx)))
      (is (= 1 (count @stored)))
      (is (cs/ends-with? (-> @stored first second)
                         "test-cust/test-cache.tgz"))))

  (testing "nothing if no cache store"
    (is (empty? @(sut/save-caches {:job {:caches [{:id "test-cache"
                                                   :path "test-path"}]}})))))

(deftest restore-caches
  (testing "restores path using blob store"
    (let [stored (atom {"test-cust/test-cache.tgz" ::dest})
          bs (h/fake-blob-store stored)
          ctx {:cache bs
               :build {:sid ["test-cust" "test-build"]}
               :job {:work-dir "work"
                     :caches [{:id "test-cache"
                               :path "test-path"}]}}]
      (is (some? @(sut/restore-caches ctx)))
      (is (empty? @stored)))))
