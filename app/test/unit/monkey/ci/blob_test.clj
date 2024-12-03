(ns monkey.ci.blob-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clompress.archivers :as ca]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as sut]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.helpers :as h]
            [monkey.oci.os
             [core :as os]
             [martian :as om]
             [stream :as oss]]))

(defmacro with-disk-blob [dir blob & body]
  `(h/with-tmp-dir ~dir
     (let [~blob (sut/make-blob-store {:blob 
                                       {:type :disk
                                        :dir (io/file ~dir "blob")}}
                                      :blob)]
       ~@body)))

(deftest disk-blob
  (testing "compresses single file to local directory"
    (with-disk-blob dir blob
      (let [f (io/file dir "out.txt")
            dest "dest.tar.gz"]
        (is (sut/blob-store? blob))
        (is (nil? (spit f "this is a save test")))
        (is (some? @(sut/save blob f dest)))
        (is (fs/exists? (io/file dir "blob" dest))))))

  (testing "can restore single file archive"
    (with-disk-blob dir blob
      (let [n "out.txt"
            f (io/file dir n)
            arch "dest.tar.gz"
            dest (io/file dir "extract")
            contents "this is a restore test"]
        (is (nil? (spit f contents)))
        (is (some? @(sut/save blob f arch)))
        (is (some? @(sut/restore blob arch dest)))
        (is (fs/exists? (io/file dest n)))
        (is (= contents (slurp (io/file dest n)))))))

  (testing "does not store if file does not exist"
    (with-disk-blob dir blob
      (is (nil? @(sut/save blob "nonexisting.txt" "/test/dest")))))

  (testing "can compress and restore file tree"
    (with-disk-blob dir blob
      (let [files {"root.txt" "this is the root file"
                   "dir/sub.txt" "this is a child file"}
            arch "dest.tar.gz"
            restore-dir (io/file dir "extract")
            src (io/file dir "src")]
        (doseq [[f c] files]
          (let [af (io/file src f)]
            (is (true? (.mkdirs (.getParentFile af))))
            (is (nil? (spit af c)))))
        (is (some? @(sut/save blob src arch)))
        (is (some? @(sut/restore blob arch restore-dir)))
        (doseq [[f c] files]
          (let [in (io/file restore-dir "src" f)]
            (is (fs/exists? in))
            (is (= c (slurp in))))))))

  (testing "can get blob stream"
    (with-disk-blob dir blob
      (let [f (io/file dir "out.txt")
            dest "dest.tar.gz"]
        (is (sut/blob-store? blob))
        (is (nil? (spit f "this is a save test")))
        (is (some? @(sut/save blob f dest)))
        (with-open [stream @(sut/input-stream blob dest)]
          (is (some? stream))))))

  (testing "`nil` stream if blob does not exist"
    (with-disk-blob dir blob
      (is (nil? @(sut/input-stream blob "nonexisting")))))

  (testing "can put raw blob stream"
    (with-disk-blob dir blob
      (let [data "this is test data"
            id "test-id"]
        (with-open [stream (bs/to-input-stream (.getBytes data))]
          (let [res @(p/put-blob-stream blob stream id)]
            (is (map? res))
            (is (some? (:dest res)))
            (is (fs/exists? (:dest res)))
            (is (= data (slurp (:dest res)))))))))

  (with-disk-blob dir blob
    (with-open [stream (bs/to-input-stream (.getBytes "test data"))]
      (is (some? @(p/put-blob-stream blob stream "test-stream"))))
    
    (testing "can get details about existing blob"
      (let [d @(p/get-blob-info blob "test-stream")]
        (is (map? d))
        (is (number? (:size d)))))

    (testing "details about non-existing blob is `nil`"
      (is (nil? @(p/get-blob-info blob "nonexisting-stream"))))))

(deftest oci-blob
  (testing "created by `make-blob-store`"
    (is (sut/blob-store? (sut/make-blob-store {:blob {:type :oci}} :blob))))
  
  (testing "`save`"
      (with-redefs [oss/input-stream->multipart (constantly (md/success-deferred nil))]
        (h/with-tmp-dir dir
          (let [tmp-dir (io/file dir "tmp")
                blob (sut/make-blob-store {:blob {:type :oci
                                                  :prefix "prefix"
                                                  :tmp-dir (u/abs-path tmp-dir)}}
                                          :blob)
                f (io/file dir "test.txt")
                _ (spit f "This is a test file")
                r @(sut/save blob f "remote/path")]
            
            (testing "writes to temp file, then uploads it"
              (is (= "prefix/remote/path" r)))

            (testing "deletes tmp file"
              (is (empty? (fs/list-dir tmp-dir)))))))

      (testing "does not store nonexisting paths"
        (is (nil? @(sut/save (sut/make-blob-store {:blob {:type :oci}} :blob) "nonexisting.txt" "/test/dest"))))

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
                    blob (sut/make-blob-store {:blob {:type :oci
                                                      :tmp-dir (u/abs-path dir)}}
                                              :blob)]
                (is (some? @(p/save-blob blob src "test/path" {:key "value"})))))))))

  (testing "`restore`"
    (h/with-tmp-dir dir
      (let [files {"test.txt" "This is a test file"
                   "dir/sub.txt" "This is a child file"}
            orig (io/file dir "orig")
            arch (io/file dir "archive.tgz")
            tmp-dir (io/file dir "tmp")
            blob (sut/make-blob-store {:blob {:type :oci
                                              :prefix "prefix"
                                              :tmp-dir (u/abs-path tmp-dir)}}
                                      :blob)
            r (io/file dir "restored")]

        ;; Create archive first
        (doseq [[f v] files]
          (let [p (io/file orig f)]
            (is (true? (.mkdirs (.getParentFile p))))
            (spit p v)))

        (testing "returns archived entries and src/dest paths"
          (let [r (sut/make-archive orig arch)]
            (is (map? r))
            (is (= orig (:src r)))
            (is (= arch (:dest r)))
            (is (not-empty (:entries r)))))
        
        (is (fs/exists? arch))
        (is (pos? (fs/size arch)))
        (with-redefs [sut/head-object (constantly true)
                      sut/get-object (constantly (md/success-deferred (fs/read-all-bytes arch)))]
          (let [res @(sut/restore blob "remote/path" r)]
            
            (testing "unzips and unarchives to destination"
              (is (not-empty (:entries res)))
              (is (= "remote/path" (:src res)))
              (is (fs/exists? (:dest res)))
              (doseq [[f v] files]
                (let [p (io/file (:dest res) "orig" f)]
                  (is (fs/exists? p))
                  (is (= v (slurp p))))))

            #_(testing "deletes tmp files"
              (is (empty? (fs/list-dir tmp-dir))))))

        (with-redefs [sut/head-object (constantly false)
                      sut/get-object (fn [& args]
                                       (throw (ex-info "This should not be invoked" {:args args})))]
          (let [res @(sut/restore blob "remote/path" r)]
            
            (testing "returns `nil` if src does not exist"
              (is (nil? res))))))))

  (testing "blob stream"
    (let [blob (sut/make-blob-store {:blob {:type :oci
                                            :prefix "prefix"}}
                                    :blob)
          path "/test/path"]

      (testing "returns raw stream"
        (with-redefs [sut/head-object (constantly true)
                      sut/get-object (constantly (md/success-deferred (.getBytes "this is a test")))]
          (is (instance? java.io.InputStream @(sut/input-stream blob path)))))

      (testing "`nil` if blob does not exist"
        (with-redefs [sut/head-object (constantly false)]
          (is (nil? @(sut/input-stream blob path)))))

      (testing "can upload raw stream"
        (with-redefs [oss/input-stream->multipart (constantly (md/success-deferred nil))]
          (is (= "prefix/test-dest" @(p/put-blob-stream blob
                                                        (bs/to-input-stream (.getBytes "test stream"))
                                                        "test-dest")))))))

  (testing "`get-blob-info` heads object"
    (let [blob (sut/make-blob-store {:blob {:type :oci}} :blob)]
      (with-redefs [om/head-object (constantly (md/success-deferred
                                                {:status 200
                                                 :headers {:content-length "1000"
                                                           :opc-meta-key "value"}}))]
        (is (= {:size 1000
                :metadata {:key "value"}}
               @(p/get-blob-info blob "test/file")))))))

