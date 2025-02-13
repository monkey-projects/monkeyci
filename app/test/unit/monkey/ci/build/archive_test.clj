(ns monkey.ci.build.archive-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [monkey.ci.blob :as blob]
            [monkey.ci.build.archive :as sut]
            [monkey.ci.helpers :as h])
  (:import java.nio.file.attribute.PosixFilePermission))

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
        (is (= #{"sub/file-1.txt"
                 "sub/file-2.txt"}
               (set (sut/list-files arch))))))))

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
          (is (not (fs/exists? (fs/path ex-dir "sub/file-1.txt"))))))))

  (testing "retains file permissions"
    (h/with-tmp-dir dir
      (let [subdir (io/file dir "sub")
            ex-dir (io/file dir "extract")
            arch (io/file dir "archive.tgz")
            p (fs/path subdir "testfile")]
        (is (.mkdirs subdir))
        (spit (fs/file p) "executable file")
        (is (= p (fs/set-posix-file-permissions
                  p
                  (conj (set (fs/posix-file-permissions p)) PosixFilePermission/OWNER_EXECUTE))))
        (is (fs/executable? p))
        (is (some? (blob/make-archive subdir arch)))
        (let [r (with-open [is (io/input-stream arch)]
                  (sut/extract is ex-dir))]
          (is (= ["sub/testfile"]
                 (:entries r)))
          (is (fs/executable? (fs/path ex-dir "sub/testfile"))))))))

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

(deftest file-mode
  (testing "can convert from posix permissions to file mode and back"
    (let [mode (Integer/parseInt "755" 8)
          posix (sut/mode->posix mode)]
      (is (= 7 (count posix)))
      (is (contains? posix java.nio.file.attribute.PosixFilePermission/OWNER_READ))
      (is (contains? posix java.nio.file.attribute.PosixFilePermission/OWNER_WRITE))
      (is (contains? posix java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE))
      (is (not (contains? posix java.nio.file.attribute.PosixFilePermission/OTHERS_WRITE)))
      (is (contains? posix java.nio.file.attribute.PosixFilePermission/GROUP_EXECUTE))
      (is (= mode (sut/posix->mode posix)))))

  (testing "can process extended modes"
    (is (not-empty (sut/mode->posix (Integer/parseInt "100644" 8))))))
