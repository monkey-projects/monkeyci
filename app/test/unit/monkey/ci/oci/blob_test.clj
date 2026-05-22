(ns monkey.ci.oci.blob-test
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.oci
             [blob :as sut]
             [core :as bo]]
            [monkey.ci.test.helpers :as h]
            [monkey.oci.os
             [martian :as om]
             [stream :as oss]]))

(deftest oci-blob
  (testing "created by `make-blob-store`"
    (is (p/blob-store? (sut/make-blob-store {:type :oci}))))
  
  (testing "`save`"
      (with-redefs [oss/input-stream->multipart (constantly (md/success-deferred nil))]
        (h/with-tmp-dir dir
          (let [tmp-dir (io/file dir "tmp")
                blob (sut/make-blob-store {:type :oci
                                           :prefix "prefix"
                                           :tmp-dir (u/abs-path tmp-dir)})
                f (io/file dir "test.txt")
                _ (spit f "This is a test file")
                r @(p/save-blob blob f "remote/path" nil)]
            
            (testing "writes to temp file, then uploads it"
              (is (= "prefix/remote/path" r)))

            (testing "deletes tmp file"
              (is (empty? (fs/list-dir tmp-dir)))))))

      (testing "does not store nonexisting paths"
        (is (nil? @(p/save-blob (sut/make-blob-store {:type :oci}) "nonexisting.txt" "/test/dest" nil))))

      (testing "passes metadata with `opc-meta-` prefix"
        (letfn [(valid-md? [[k _]]
                  (.startsWith (name k) "opc-meta-"))]
          (with-redefs [oss/input-stream->multipart
                        (fn [_ {:keys [metadata]}]
                          (if (and (not-empty metadata)
                                   (every? valid-md? metadata))
                            (md/success-deferred nil)
                            (md/error-deferred (ex-info "Invalid metadata" metadata))))]
            (h/with-tmp-dir dir
              (let [src (io/file dir "test.txt")
                    _ (spit src "test file")
                    blob (sut/make-blob-store {:type :oci
                                               :tmp-dir (u/abs-path dir)})]
                (is (some? @(p/save-blob blob src "test/path" {:key "value"})))))))))

  (testing "`restore`"
    (h/with-tmp-dir dir
      (let [files {"test.txt" "This is a test file"
                   "dir/sub.txt" "This is a child file"}
            orig (io/file dir "orig")
            arch (io/file dir "archive.tgz")
            tmp-dir (io/file dir "tmp")
            blob (sut/make-blob-store {:type :oci
                                       :prefix "prefix"
                                       :tmp-dir (u/abs-path tmp-dir)})
            r (io/file dir "restored")]

        ;; Create archive first
        (doseq [[f v] files]
          (let [p (io/file orig f)]
            (is (true? (.mkdirs (.getParentFile p))))
            (spit p v)))

        (testing "returns archived entries and src/dest paths"
          (let [r (blob/make-archive orig arch)]
            (is (map? r))
            (is (= orig (:src r)))
            (is (= arch (:dest r)))
            (is (not-empty (:entries r)))))
        
        (is (fs/exists? arch))
        (is (pos? (fs/size arch)))
        (with-redefs [sut/head-object (constantly true)
                      sut/get-object (constantly (md/success-deferred (fs/read-all-bytes arch)))]
          (let [res @(p/restore-blob blob "remote/path" r)]
            
            (testing "unzips and unarchives to destination"
              (is (not-empty (:entries res)))
              (is (= "remote/path" (:src res)))
              (is (fs/exists? (:dest res)))
              (doseq [[f v] files]
                (let [p (io/file (:dest res) f)]
                  (is (fs/exists? p))
                  (is (= v (slurp p))))))

            (testing "deletes tmp files"
              (is (not (fs/exists? tmp-dir))))))

        (with-redefs [sut/head-object (constantly false)
                      sut/get-object (fn [& args]
                                       (throw (ex-info "This should not be invoked" {:args args})))]
          (let [res @(p/restore-blob blob "remote/path" r)]
            
            (testing "returns `nil` if src does not exist"
              (is (nil? res))))))))

  (testing "blob stream"
    (let [blob (sut/make-blob-store {:type :oci
                                     :prefix "prefix"})
          path "/test/path"]

      (testing "returns raw stream"
        (with-redefs [sut/head-object (constantly true)
                      sut/get-object (constantly (md/success-deferred (.getBytes "this is a test")))]
          (is (instance? java.io.InputStream @(p/get-blob-stream blob path)))))

      (testing "`nil` if blob does not exist"
        (with-redefs [sut/head-object (constantly false)]
          (is (nil? @(p/get-blob-stream blob path)))))

      (testing "can upload raw stream"
        (with-redefs [oss/input-stream->multipart (constantly (md/success-deferred nil))]
          (is (= "prefix/test-dest" @(p/put-blob-stream blob
                                                        (bs/to-input-stream (.getBytes "test stream"))
                                                        "test-dest")))))))

  (testing "`get-blob-info` heads object"
    (let [blob (sut/make-blob-store {:type :oci})]
      (with-redefs [om/head-object (constantly (md/success-deferred
                                                {:status 200
                                                 :headers {:content-length "1000"
                                                           :opc-meta-key "value"}}))]
        (is (= {:size 1000
                :metadata {:key "value"}}
               @(p/get-blob-info blob "test/file")))))))
