(ns monkey.ci.test.blob-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clompress.archivers :as ca]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as sut]
             [utils :as u]]
            [monkey.ci.test.helpers :as h]
            [monkey.oci.os.core :as os]))

(defn blob-store? [x]
  (satisfies? sut/BlobStore x))

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
        (is (blob-store? blob))
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
            (is (= c (slurp in)))))))))

(deftest oci-blob
  (testing "created by `make-blob-store`"
    (is (blob-store? (sut/make-blob-store {:blob {:type :oci}} :blob))))
  
  (testing "`save`"
      (with-redefs [os/put-object (constantly (md/success-deferred nil))]
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
              (is (empty? (fs/list-dir tmp-dir))))))))

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
        (is (nil? (sut/make-archive orig arch)))
        (is (fs/exists? arch))
        (is (pos? (fs/size arch)))
        (with-redefs [os/get-object (constantly (md/success-deferred (fs/read-all-bytes arch)))]
          (let [res @(sut/restore blob "remote/path" r)]
            
            (testing " reads to temp file, then unarchives it"
              (is (= r res))
              (doseq [[f v] files]
                (let [p (io/file r "orig" f)]
                  (is (fs/exists? p))
                  (is (= v (slurp p))))))

            (testing "deletes tmp files"
              (is (empty? (fs/list-dir tmp-dir))))))))))
