(ns monkey.ci.artifacts-test
  (:require [babashka.fs :as fs]
            [clojure
             [string :as cs]
             [test :refer [deftest is testing]]]
            [clojure.java.io :as io]
            [monkey.ci
             [artifacts :as sut]
             [protocols :as p]]
            [monkey.ci.blob.disk :as blob]
            [monkey.ci.build
             [api :as api]
             [api-server :as bas]]
            [monkey.ci.test
             [api-server :as ta]
             [blob :as tb]
             [helpers :as h]]))

(deftest save-artifacts
  (testing "saves path using blob store, relative to job work dir"
    (h/with-tmp-dir dir
      (let [p (doto (fs/path dir "test-path")
                (fs/create-file))
            stored (atom {})
            sid ["test-cust" "test-build"]
            bs (h/fake-blob-store stored)
            repo (sut/make-blob-repository bs sid)
            ctx {:artifacts repo
                 :job {:work-dir dir
                       :save-artifacts [{:id "test-artifact"
                                         :path "test-path"}]}}]
        (is (some? @(sut/save-artifacts ctx)))
        (is (= 1 (count @stored)))
        (let [[dest p] (first @stored)]
          (is (cs/ends-with? dest "test-cust/test-build/test-artifact.tgz"))
          (is (= (str dir "/test-path") p))))))

  (testing "nothing if no artifact repo"
    (is (empty? @(sut/save-artifacts
                  {:job {:save-artifacts [{:id "test-artifact"
                                           :path "test-path"}]}})))))

(deftest restore-artifacts
  (testing "restores path using artifact repo"
    (let [job {:work-dir "work"
               :restore-artifacts [{:id "test-artifact"
                                    :path "test-path"}]}
          stored (atom {"test-cust/test-build/test-artifact.tgz" (str (fs/canonicalize
                                                                       (fs/path (:work-dir job) "test-path")))})
          bs (h/strict-fake-blob-store stored)
          sid ["test-cust" "test-build"]
          ctx {:artifacts (sut/make-blob-repository bs sid)
               :job job}]
      (is (not-empty @(sut/restore-artifacts ctx)))
      (is (empty? @stored) "expected entry to be restored"))))

(deftest restore-blob
  (testing "returns paths as strings and entry count"
    (let [dest (io/file "dest")
          art-id "test-artifact"
          sid ["test-sid"]
          src (io/file "test-sid" (str art-id ".tgz"))
          bs (h/fake-blob-store (atom {src dest}))   
          art (sut/make-blob-repository bs sid)
          r @(sut/restore-blob {:repo art}
                               {:id art-id
                                :path "dest"})]
      (is (map? r))
      (is (= (str (fs/canonicalize src)) (:src r)))
      (is (= (.getCanonicalPath (.getAbsoluteFile dest)) (:dest r)))
      (is (number? (:entries r))))))

(deftest blob-artifact-repository
  (let [sid (repeatedly 3 (comp str random-uuid))
        store (h/fake-blob-store)
        src-art "test source artifact"
        art-id (str (random-uuid))]

    (testing "with sid"
      (let [repo (sut/make-blob-repository store sid)]
        (testing "uploads artifact to blob store"
          (is (some? @(p/save-artifact repo sid art-id src-art)))
          (is (= 1 (-> store :stored deref count))))
        
        (testing "downloads artifact via blob store"
          (is (some? @(p/restore-artifact repo sid art-id ::test-destination)))

          (is (empty? (-> store :stored deref))))))

    (testing "without sid"
      (let [repo (sut/make-blob-repository store)]
        (testing "uploads artifact to blob store"
          (is (some? @(p/save-artifact repo sid art-id src-art)))
          (is (= 1 (-> store :stored deref count))))
        
        (testing "downloads artifact via blob store"
          (is (some? @(p/restore-artifact repo sid art-id ::test-destination)))

          (is (empty? (-> store :stored deref))))))))

(deftest build-api-artifact-repository
  (h/with-tmp-dir dir
    (let [store-dir (fs/path dir "store")
          _ (fs/create-dir store-dir)
          store (blob/->DiskBlobStore (str store-dir))
          sid (repeatedly 3 (comp str random-uuid))
          build {:sid sid}
          server (-> (ta/test-config)
                     (assoc :artifacts store
                            :build build)
                     (bas/start-server))
          client (api/make-client (format "http://localhost:%d" (:port server)) (:token server))
          art-id (str (random-uuid))
          in-dir (fs/path dir "input")
          out-dir (fs/path dir "output")
          _ (fs/create-dir in-dir)
          _ (spit (fs/file (fs/path in-dir "test.txt")) "This is a test file")
          repo (sut/make-build-api-repository client)]
      (with-open [s (:server server)]
        (testing "uploads artifact using api"
          (is (= art-id (:artifact-id @(p/save-artifact repo sid art-id (str in-dir))))))
        
        (testing "downloads artifact using api"
          (is (some? @(p/restore-artifact repo sid art-id (str out-dir))))
          (is (fs/exists? (fs/path out-dir "test.txt"))))

        (testing "does nothing if artifact does not exist"
          (is (nil? @(p/restore-artifact repo sid "nonexisting" (str out-dir)))))))))

(deftest restore-interceptor
  (let [blob (tb/test-store {"test/build/test-art.tgz" {:file "/tmp/test.txt"}})
        sid ["test" "build"]
        job {:id "test-job"
             :restore-artifacts [{:id "test-art"
                                  :path "test/path"}]}
        art (sut/make-blob-repository blob sid)
        {:keys [enter] :as i} (sut/restore-interceptor ::job-ctx)]
    (is (keyword? (:name i)))
    
    (testing "`enter` restores artifacts for job using repository"
      (let [r (-> {::job-ctx {:job job
                              :artifacts art}}
                  (enter)
                  (sut/get-restored)
                  first)]
        (is (= "/tmp/test.txt" (:dest r)))
        (is (cs/ends-with? (:src r) "test/build/test-art.tgz"))))))

(deftest save-interceptor
  (let [blob (tb/test-store)
        sid ["test" "build"]
        job {:id "test-job"
             :save-artifacts [{:id "test-art"
                               :path "test/path"}]}
        art (sut/make-blob-repository blob sid)
        {:keys [enter] :as i} (sut/save-interceptor ::job-ctx)]
    (is (keyword? (:name i)))
    
    (testing "`enter` saves artifacts for job using repository"
      (is (= 1 
             (-> {::job-ctx {:job job
                             :checkout-dir "/tmp"
                             :artifacts art}}
                 (enter)
                 (sut/get-saved)
                 (count))))
      (is (= "/tmp/test/path" (-> (tb/stored blob)
                                  (get "test/build/test-art.tgz")
                                  :file))))))
