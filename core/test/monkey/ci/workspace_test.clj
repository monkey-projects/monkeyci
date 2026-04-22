(ns monkey.ci.workspace-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [protocols :as p]
             [workspace :as sut]]
            [monkey.ci.test.helpers :as h]))

(deftest restore
  (testing "restores to checkout dir"
    (let [stored (atom {"test-cust/test-repo.tgz" "local"})
          store (h/strict-fake-blob-store stored)
          ws (sut/->BlobWorkspace store "local")
          rt {:sid ["test-cust" "test-repo"]
              :workspace ws}]
      (is (true? (-> (sut/restore rt)
                     (deref)
                     :workspace/restored?)))
      (is (empty? @stored)))))

(deftest build-api-workspace
  (testing "downloads and extracts workspace via client"
    (h/with-tmp-dir dir
      (let [src (fs/create-dir (fs/path dir "src"))
            dest (fs/create-dirs (fs/path dir "dest"))
            arch (fs/path dir "archive.tgz")
            contents "This is a test file"
            client (fn [_]
                     (md/success-deferred {:status 200
                                           :body (io/input-stream (fs/file arch))}))
            ws (sut/make-build-api-workspace client (str dest))]
        (is (nil? (spit (fs/file (fs/path src "test.txt")) contents)))
        (is (not-empty (:entries (blob/make-archive (fs/file src) (fs/file arch)))))
        (is (fs/exists? arch))
        (is (not-empty (:entries @(p/restore-workspace ws nil))))
        (is (fs/exists? (fs/path dest "test.txt")))))))
