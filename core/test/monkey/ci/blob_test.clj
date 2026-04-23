(ns monkey.ci.blob-test
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as sut]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.test.helpers :as h]))

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
      (let [n "single.txt"
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
        (is (not-empty (:entries @(sut/save blob src arch))))
        (is (some? @(sut/restore blob arch restore-dir)))
        (doseq [[f c] files]
          (let [in (io/file restore-dir f)]
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



