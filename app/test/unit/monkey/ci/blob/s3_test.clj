(ns monkey.ci.blob.s3-test
  (:require [clojure.test :refer [deftest testing is]]
            [amazonica.aws.s3 :as s3]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [monkey.ci
             [blob :as blob]
             [protocols :as p]]
            [monkey.ci.blob.s3 :as sut]
            [monkey.ci.build.archive :as a]
            [monkey.ci.test.helpers :as h]))

(def input-stream? (partial instance? java.io.InputStream))

(deftest s3-blob-store
  (h/with-tmp-dir dir
    (let [store (blob/make-blob-store {:store
                                       {:type :s3
                                        :bucket-name "test-bucket"}}
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
          (with-redefs [s3/put-object (fn [opts details]
                                        (reset! inv {:metadata
                                                     {:opts opts
                                                      :details details}}))]
            (testing "puts archive in bucket"
              (is (some? @(blob/save store src "test/dest")))
              (is (some? @inv))
              (let [details (-> @inv :metadata :details)]
                (is (= "test-bucket" (:bucket-name details)))
                (is (= "test/dest" (:key details)))
                (is (string? (:file details))))))))

      (testing "`restore-blob` downloads and extracts"
        (let [inv (atom nil)
              dest (fs/path dir "extracted")]
          (with-redefs [s3/get-object (fn [opts bucket path]
                                        (reset! inv {:metadata
                                                     {:opts opts
                                                      :details {:bucket-name bucket
                                                                :key path}}})
                                        {:input-stream (io/input-stream arch)})]
            (is (some? @(blob/restore store "test/src" (str dest))))
            (is (fs/exists? dest))
            (is (= "test file" (slurp (str (fs/path dest "test.txt")))))
            (is (some? @inv)))))

      (testing "`get-blob-stream` returns blob as input stream"
        (let [dest (fs/path dir "extracted")]
          (with-redefs [s3/get-object (fn [opts bucket path]
                                        {:input-stream (io/input-stream arch)})]
            (with-open [res @(p/get-blob-stream store "test/src")]
              (is (input-stream? res))
              (is (some? (a/extract res (str dest))))
              (is (= "test file" (slurp (str (fs/path dest "test.txt")))))))))

      (testing "`put-blob-stream` directly uploads the stream to bucket"
        (let [inv (atom nil)]
          (with-redefs [s3/put-object (fn [opts details]
                                        (reset! inv {:metadata
                                                     {:opts opts
                                                      :details details}}))]
            (testing "puts archive in bucket"
              (let [stream (java.io.ByteArrayInputStream. (.getBytes "test stream"))]
                (is (some? @(p/put-blob-stream store stream "test/dest")))
                (is (some? @inv))
                (let [details (-> @inv :metadata :details)]
                  (is (= "test-bucket" (:bucket-name details)))
                  (is (= "test/dest" (:key details)))
                  (is (= stream (:input-stream details)))))))))

      (testing "`get-blob-info`"
        (let [inv (atom nil)]
          (with-redefs [s3/get-object-metadata
                        (fn [opts details]
                          (reset! inv {:opts opts
                                       :details details
                                       ;; s3 lib uses joda time
                                       :last-modified (org.joda.time.DateTime.)}))]
            (let [res @(p/get-blob-info store "test/src")]
              (testing "fetches object metadata"
                (is (some? @inv)))

              (testing "returns original result in response"
                (is (= {:bucket-name "test-bucket"
                        :key "test/src"}
                       (-> res
                           :result
                           :details))))

              (testing "converts last-modified to java instant"
                (is (instance? java.time.Instant (:last-modified res)))))))))))
