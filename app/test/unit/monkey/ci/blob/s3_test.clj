(ns monkey.ci.blob.s3-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [java-time.api :as jt]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [protocols :as p]]
            [monkey.ci.blob
             [minio :as minio]
             [s3 :as sut]]
            [monkey.ci.build.archive :as a]
            [monkey.ci.test.helpers :as h]))

(def input-stream? (partial instance? java.io.InputStream))

(deftest s3-blob-store
  (h/with-tmp-dir dir
    (let [conf {:type :s3
                :endpoint "http://test"
                :access-key "testkey"
                :secret-key "testsecret"
                :bucket-name "test-bucket"}
          store (blob/make-blob-store {:store conf}
                                      :store)
          src (str (fs/create-dirs (fs/path dir "src")))
          archive-dir (str (fs/create-dirs (fs/path dir "archives")))
          arch (fs/file (fs/path archive-dir "to-restore.tgz"))]
      (is (nil? (spit (fs/file (fs/path src "test.txt")) "test file")))
      (is (some? (blob/make-archive src arch)))
      
      (testing "can create store"
        (is (blob/blob-store? store)))

      (testing "`save-blob`"
        (let [inv (atom nil)]
          (with-redefs [minio/put-object (fn [client bucket path opts]
                                           (reset! inv {:opts opts
                                                        :bucket bucket
                                                        :path path}))]
            (testing "puts archive in bucket"
              (is (some? @(blob/save store src "test/dest")))
              (is (some? @inv))
              (let [details (-> @inv :opts)]
                (is (= "test-bucket" (:bucket @inv)))
                (is (= "test/dest" (:path @inv)))
                (is (string? (:file details)))))

            (testing "prepends prefix to key"
              (let [store (blob/make-blob-store {:store
                                                 (assoc conf :prefix "test-prefix/")}
                                                :store)]
                (is (some? @(blob/save store src "test/dest")))
                (is (some? @inv))
                (is (= "test-prefix/test/dest" (:path @inv))))))))

      (testing "`restore-blob`"
        (let [inv (atom nil)
              dest (fs/path dir "extracted")
              exists? (atom true)]
          (with-redefs [minio/get-object (fn [client bucket path]
                                           (reset! inv {:client client
                                                        :details {:bucket-name bucket
                                                                  :key path}})
                                           (md/success-deferred (io/input-stream arch)))
                        minio/object-exists? (fn [client bucket path]
                                               (md/success-deferred @exists?))]
            (testing "downloads and extracts"
              (is (some? @(blob/restore store "test/src" (str dest))))
              (is (fs/exists? dest))
              (is (= "test file" (slurp (str (fs/path dest "test.txt")))))
              (is (some? @inv)))

            (testing "prepends prefix"
              (let [store (blob/make-blob-store {:store
                                                 (assoc conf :prefix "test-prefix/")}
                                                :store)]
                (is (some? @(blob/restore store "test/src" (str dest))))
                (is (= "test-prefix/test/src" (-> @inv
                                                  :details
                                                  :key)))))

            (testing "`nil` when object does not exist"
              (is (false? (reset! exists? false)))
              (is (nil? @(blob/restore store "test/src" (str dest))))))))

      (testing "`get-blob-stream` returns blob as input stream"
        (let [dest (fs/path dir "extracted")]
          (with-redefs [minio/get-object (fn [opts bucket path]
                                           (md/success-deferred (io/input-stream arch)))
                        minio/object-exists? (constantly (md/success-deferred true))]
            (with-open [res @(p/get-blob-stream store "test/src")]
              (is (input-stream? res))
              (is (some? (a/extract res (str dest))))
              (is (= "test file" (slurp (str (fs/path dest "test.txt")))))))))

      (testing "`put-blob-stream` directly uploads the stream to bucket"
        (let [inv (atom nil)]
          (with-redefs [minio/put-object (fn [client bucket path opts]
                                           (reset! inv {:opts opts
                                                        :bucket bucket
                                                        :path path}))]
            (testing "puts archive in bucket"
              (let [stream (java.io.ByteArrayInputStream. (.getBytes "test stream"))]
                (is (some? @(p/put-blob-stream store stream "test/dest")))
                (is (some? @inv))
                (is (= "test-bucket" (:bucket @inv)))
                (is (= "test/dest" (:path @inv)))
                (is (= stream (-> @inv :opts :stream))))))))

      (testing "`get-blob-info`"
        (let [inv (atom nil)]
          (with-redefs [minio/get-object-details
                        (fn [_ bucket path]
                          (reset! inv {:bucket bucket
                                       :path path
                                       :last-modified (jt/zoned-date-time)}))
                        minio/object-exists? (constantly (md/success-deferred true))]
            (let [res @(p/get-blob-info store "test/src")]
              (testing "fetches object metadata"
                (is (some? @inv)))

              (testing "returns object details"
                (is (= "test/src" (:src res))))

              (testing "converts last-modified to java instant"
                (is (instance? java.time.Instant (:last-modified res)))))))))))
