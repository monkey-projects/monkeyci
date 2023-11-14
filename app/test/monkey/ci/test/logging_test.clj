(ns monkey.ci.test.logging-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [monkey.ci.logging :as sut]
            [monkey.ci.test.helpers :as h]))

(def file? (partial instance? java.io.File))

(deftest make-logger
  (testing "type `inherit`"
    (let [maker (sut/make-logger {:type :inherit})
          l (maker {} [])]
      
      (testing "creates logger that always returns `:inherit`"
        (is (= :inherit (sut/log-output l))))

      (testing "doesn't handle streams"
        (is (nil? (sut/handle-stream l :test))))))

  (testing "type `file`"
    (h/with-tmp-dir dir
      (let [maker (sut/make-logger {:type :file
                                    :dir dir})]
        
        (testing "creates logger that returns file name"
          (let [l (maker {} ["test.txt"])
                f (sut/log-output l)]
            (is (file? f))
            (is (= (io/file dir "test.txt") f))))

        (testing "defaults to subdir from context work dir"
          (let [maker (sut/make-logger {:type :file})
                l (maker {:work-dir dir} ["test.txt"])
                f (sut/log-output l)]
            (is (file? f))
            (is (= (io/file dir "logs" "test.txt") f))))

        (testing "creates parent dir"
          (let [l (maker {} ["logs" "test.txt"])
                f (sut/log-output l)]
            (is (.exists (.getParentFile f)))))

        (testing "doesn't handle streams"
          (is (nil? (-> (maker {} ["test.log"])
                        (sut/handle-stream :test))))))))

  (testing "type `oci`"
    (testing "creates logger that outputs to streams"
      (let [maker (sut/make-logger {:type :oci
                                    :bucket-name "test-bucket"})
            l (maker {} ["test.log"])]
        (is (= :stream (sut/log-output l)))))))
