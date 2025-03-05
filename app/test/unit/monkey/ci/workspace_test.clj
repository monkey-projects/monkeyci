(ns monkey.ci.workspace-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [manifold.deferred :as md]
   [monkey.ci.blob :as blob]
   [monkey.ci.protocols :as p]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.workspace :as sut]))

(deftest restore
  (testing "nothing if no workspace in build"
    (let [rt {}]
      (is (= rt @(sut/restore rt)))))

  (testing "restores using blob with the workspace path in build into checkout dir"
    (let [stored (atom {"path/to/workspace" "local"})
          store (h/strict-fake-blob-store stored)
          rt {:build {:workspace "path/to/workspace"
                      :checkout-dir "local/dir"}
              :workspace store}]
      (is (true? (-> (sut/restore rt)
                     (deref)
                     (get-in [:build :workspace/restored?]))))
      (is (empty? @stored))))

  (testing "restores using workspace given"
    (let [stored (atom {"path/to/workspace" "local"})
          store (h/strict-fake-blob-store stored)
          build {:workspace "path/to/workspace"
                 :checkout-dir "local/dir"}
          ws (sut/->BlobWorkspace store build)
          rt {:build build
              :workspace ws}]
      (is (true? (-> (sut/restore rt)
                     (deref)
                     (get-in [:build :workspace/restored?]))))
      (is (empty? @stored)))))

(deftest build-api-workspace
  (testing "downloads and extracts workspace via client"
    (h/with-tmp-dir dir
      (let [src (fs/create-dir (fs/path dir "src"))
            dest (fs/create-dirs (fs/path dir "dest/src"))
            arch (fs/path dir "archive.tgz")
            contents "This is a test file"
            build {:checkout-dir (str dest)}
            client (fn [_]
                     (md/success-deferred {:status 200
                                           :body (io/input-stream (fs/file arch))}))
            ws (sut/make-build-api-workspace client build)]
        (is (nil? (spit (fs/file (fs/path src "test.txt")) contents)))
        (is (not-empty (:entries (blob/make-archive (fs/file src) (fs/file arch)))))
        (is (fs/exists? arch))
        (is (not-empty (:entries @(p/restore-workspace ws))))
        (is (fs/exists? (fs/path dest "test.txt")))))))
