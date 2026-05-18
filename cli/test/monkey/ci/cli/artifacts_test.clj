(ns monkey.ci.cli.artifacts-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.cli.artifacts :as sut]))

(deftest save-artifact
  (fs/with-temp-dir [dir]
    (let [src (fs/create-dirs (fs/path dir "src"))
          dst (fs/create-dirs (fs/path dir "dest"))]
      (testing "copies `src` to `dest`"
        (testing "file"
          (let [f (fs/path src "test.txt")
                contents "test file"
                exp (fs/path dst "test-id" "test.txt")]
            (is (nil? (spit (fs/file f) contents)))
            (is (= {:src f
                    :dest exp
                    :id "test-id"
                    :path "test.txt"}
                   (sut/save-artifact src dst {:id "test-id" :path "test.txt"})))
            (is (fs/exists? exp))
            (is (= contents (slurp (fs/file exp))))))

        (testing "directory tree"
          (let [f (fs/create-dirs (fs/path src "sub"))
                contents "test file"
                exp (fs/path dst "test-id" "sub")]
            (is (nil? (spit (fs/file f "test.txt") contents)))
            (is (= {:src f
                    :dest exp
                    :id "test-id"
                    :path "sub"}
                   (sut/save-artifact src dst {:id "test-id" :path "sub"})))
            (is (fs/exists? exp))
            (is (= contents (slurp (fs/file exp "test.txt"))))))))))

(deftest restore-artifact
  (fs/with-temp-dir [dir]
    (testing "copies `src` to `dest`"
      (let [src (fs/create-dirs (fs/path dir "src"))
            dst (fs/create-dirs (fs/path dir "dest"))
            f (fs/path (fs/create-dirs (fs/path src "test-id")) "test.txt")
            contents "test file"
            exp (fs/path dst "test.txt")]
        (is (nil? (spit (fs/file f) contents)))
        (is (= {:src (fs/path src "test-id" ".")
                :dest (fs/path dst ".")
                :id "test-id"
                :path "."}
               (sut/restore-artifact src dst {:id "test-id" :path "."})))
        (is (fs/exists? exp))
        (is (= contents (slurp (fs/file exp))))))))
