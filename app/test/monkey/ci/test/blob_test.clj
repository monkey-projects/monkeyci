(ns monkey.ci.test.blob-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [monkey.ci.blob :as sut]
            [monkey.ci.test.helpers :as h]))

(defn blob-store? [x]
  (satisfies? sut/BlobStore x))

(defmacro with-disk-blob [dir blob & body]
  `(h/with-tmp-dir ~dir
     (let [~blob (sut/make-blob-store {:blob 
                                       {:type :disk
                                        :dir (io/file ~dir "blob")}})]
       ~@body)))

(deftest disk-store
  (testing "compresses single file to local directory"
    (with-disk-blob dir blob
      (let [f (io/file dir "out.txt")
            dest "dest.tar.gz"]
        (is (blob-store? blob))
        (is (nil? (spit f "this is a save test")))
        (is (some? (sut/save blob f dest)))
        (is (fs/exists? (io/file dir "blob" dest))))))

  (testing "can restore single file archive"
    (with-disk-blob dir blob
      (let [n "out.txt"
            f (io/file dir n)
            arch "dest.tar.gz"
            dest (io/file dir "extract")
            contents "this is a restore test"]
        (is (nil? (spit f contents)))
        (is (some? (sut/save blob f arch)))
        (is (some? (sut/restore blob arch dest)))
        (is (fs/exists? (io/file dest n)))
        (is (= contents (slurp (io/file dest n))))))))
