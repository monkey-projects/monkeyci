(ns monkey.ci.build.archive-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [monkey.ci.blob :as blob]
            [monkey.ci.build.archive :as sut]
            [monkey.ci.helpers :as h]))

(deftest list-files
  (testing "lists files in archive"
    (h/with-tmp-dir dir
      (let [subdir (io/file dir "sub")
            arch (io/file dir "archive.tgz")]
        (is (.mkdirs subdir))
        (spit (io/file subdir "file-1.txt") "first file")
        (spit (io/file subdir "file-2.txt") "second file")
        (is (= 3 (-> (blob/make-archive subdir arch)
                     :entries
                     count))
            "entry for each file and the dir")
        (is (= ["sub/file-1.txt"
                "sub/file-2.txt"]
               (sut/list-files arch)))))))

(deftest extract
  (testing "extracts files matching regex"
    (h/with-tmp-dir dir
      (let [subdir (io/file dir "sub")
            ex-dir (io/file dir "extract")
            arch (io/file dir "archive.tgz")]
        (is (.mkdirs subdir))
        (spit (io/file subdir "file-1.txt") "first file")
        (spit (io/file subdir "file-2.txt") "another file")
        (is (some? (blob/make-archive subdir arch)))
        (let [r (with-open [is (io/input-stream arch)]
                  (sut/extract is ex-dir #".*-2.txt$"))]
          (is (= ["sub/file-2.txt"]
                 (:entries r)))
          (is (fs/exists? (fs/path ex-dir "sub/file-2.txt")))
          (is (not (fs/exists? (fs/path ex-dir "sub/file-1.txt")))))))))

(deftest extract+read
  (testing "extracts single file from archive into memory"
    (h/with-tmp-dir dir
      (let [subdir (io/file dir "sub")
            ex-dir (io/file dir "extract")
            arch (io/file dir "archive.tgz")]
        (is (.mkdirs subdir))
        (spit (io/file subdir "file-1.txt") "first file")
        (spit (io/file subdir "file-2.txt") "another file")
        (is (some? (blob/make-archive subdir arch)))
        (let [r (with-open [is (io/input-stream arch)]
                  (sut/extract+read is #".*-2.txt$"))]
          (is (= "another file" r)))))))
