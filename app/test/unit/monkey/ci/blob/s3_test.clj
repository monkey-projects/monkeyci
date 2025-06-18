(ns monkey.ci.blob.s3-test
  (:require [clojure.test :refer [deftest testing is]]
            [amazonica.aws.s3 :as s3]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [monkey.ci.blob :as blob]
            [monkey.ci.blob.s3 :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest s3-blob-store
  (h/with-tmp-dir dir
    (let [store (blob/make-blob-store {:store
                                       {:type :s3
                                        :bucket-name "test-bucket"}}
                                      :store)
          src (str (fs/create-dirs (fs/path dir "src")))
          archive-dir (str (fs/create-dirs (fs/path dir "archives")))]
      (is (nil? (spit (fs/file (fs/path src "test.txt")) "test file")))
      
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
              arch (fs/file (fs/path archive-dir "to-restore.tgz"))
              dest (fs/path dir "extracted")]
          (is (some? (blob/make-archive src arch)))
          (with-redefs [s3/get-object (fn [opts bucket path]
                                        (reset! inv {:metadata
                                                     {:opts opts
                                                      :details {:bucket-name bucket
                                                                :key path}}})
                                        {:input-stream (io/input-stream arch)})]
            (is (some? @(blob/restore store "test/src" (str dest))))
            (is (fs/exists? dest))
            (is (= "test file" (slurp (str (fs/path dest "test.txt")))))
            (is (some? @inv))))))))
