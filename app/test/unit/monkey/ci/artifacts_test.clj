(ns monkey.ci.artifacts-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as sut]
             [blob :as blob]]
            [monkey.ci.build
             [api :as api]
             [api-server :as bas]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.api-server :as ta]))

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
        (let [[dest p] (first @stored)]
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

(deftest restore-blob
  (testing "returns paths as strings and entry count"
    (let [src (io/file "src")
          dest (io/file "dest")
          bs (h/fake-blob-store (atom {src dest}))
          r @(sut/restore-blob {:store bs
                                :build-path (constantly src)}
                               {:id "test-cache"
                                :path "test-path"})]
      (is (map? r))
      (is (= (.getCanonicalPath src) (:src r)))
      (is (= (.getCanonicalPath (.getParentFile (.getAbsoluteFile dest))) (:dest r)))
      (is (number? (:entries r))))))

(deftest blob-artifact-repository
  (let [build {:sid (take 3 (repeatedly (comp str random-uuid)))}
        store (h/fake-blob-store)
        repo (sut/->BlobArtifactRepository store build)
        src-art "test source artifact"
        art-id (str (random-uuid))]

    (testing "uploads artifact to blob store"
      (is (some? @(sut/upload-artifact repo art-id src-art)))
      (is (= 1 (-> store :stored deref count))))
    
    (testing "downloads artifact via blob store"
      (is (some? @(sut/download-artifact repo art-id ::test-destination)))

      (is (empty? (-> store :stored deref))))))

(deftest build-api-artifact-repository
  (h/with-tmp-dir dir
    (let [store-dir (fs/path dir "store")
          _ (fs/create-dir store-dir)
          store (blob/->DiskBlobStore (str store-dir))
          build {:sid (take 3 (repeatedly (comp str random-uuid)))}
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
          repo (sut/->BuildApiArtifactRepository client)]
      (with-open [s (:server server)]
        (testing "uploads artifact using api"
          (is (= art-id (:artifact-id @(sut/upload-artifact repo art-id (str in-dir))))))
        
        (testing "downloads artifact using api"
          (is (some? @(sut/download-artifact repo art-id (str out-dir))))
          (is (fs/exists? (fs/path out-dir "input" "test.txt"))))))))
