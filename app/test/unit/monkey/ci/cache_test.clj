(ns monkey.ci.cache-test
  (:require
   [clojure.string :as cs]
   [clojure.test :refer [deftest is testing]]
   [monkey.ci.blob :as blob]
   [monkey.ci.cache :as sut]
   [monkey.ci.test.blob :as tb]
   [monkey.ci.test.helpers :as h]))

(deftest save-caches
  (testing "saves path using blob store"
    (let [stored (atom {})
          bs (h/fake-blob-store stored)
          sid ["test-cust" "test-build"]
          repo (sut/make-blob-repository bs sid)
          ctx {:cache repo
               :job {:work-dir "work"
                     :caches [{:id "test-cache"
                               :path "test-path"}]}}]
      (is (some? @(sut/save-caches ctx)))
      (is (= 1 (count @stored)))
      (is (cs/ends-with? (-> @stored ffirst)
                         "test-cust/test-cache.tgz"))))

  (testing "nothing if no cache store"
    (is (empty? @(sut/save-caches {:job {:caches [{:id "test-cache"
                                                   :path "test-path"}]}})))))

(deftest restore-caches
  (testing "restores path using blob store"
    (let [stored (atom {"test-cust/test-cache.tgz" ::dest})
          bs (h/fake-blob-store stored)
          sid ["test-cust" "test-build"]
          repo (sut/make-blob-repository bs sid)
          ctx {:cache repo
               :job {:work-dir "work"
                     :caches [{:id "test-cache"
                               :path "test-path"}]}}]
      (is (some? @(sut/restore-caches ctx)))
      (is (empty? @stored)))))

(deftest restore-interceptor
  (let [blob (tb/test-store {"test/test-cache.tgz" {:file "/tmp/test.txt"}})
        sid ["test" "build"]
        job {:id "test-job"
             :caches [{:id "test-cache"
                       :path "test/path"}]}
        cache (sut/make-blob-repository blob sid)
        {:keys [enter] :as i} (sut/restore-interceptor ::job-ctx)]
    (is (keyword? (:name i)))
    
    (testing "`enter` restores caches for job using repository"
      (let [r (-> {::job-ctx {:job job
                              :checkout-dir "/tmp"
                              :cache cache}}
                  (enter)
                  (sut/get-restored)
                  first)]
        (is (= "/tmp/test.txt" (:dest r)))
        (is (cs/ends-with? (:src r) "test/test-cache.tgz"))))))

(deftest save-interceptor
  (let [blob (tb/test-store)
        sid ["test" "build"]
        job {:id "test-job"
             :caches [{:id "test-cache"
                       :path "test/path"}]}
        cache (sut/make-blob-repository blob sid)
        {:keys [enter] :as i} (sut/save-interceptor ::job-ctx)]
    (is (keyword? (:name i)))
    
    (testing "`enter` saves caches for job using repository"
      (is (= 1 
             (-> {::job-ctx {:job job
                             :cache cache
                             :checkout-dir "/tmp"}}
                 (enter)
                 (sut/get-saved)
                 (count))))
      (is (= "/tmp/test/path" (-> (tb/stored blob)
                                  (get "test/test-cache.tgz")
                                  :file))))))
