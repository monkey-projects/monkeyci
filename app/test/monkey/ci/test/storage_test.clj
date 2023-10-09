(ns monkey.ci.test.storage-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci.storage :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest file-storage
  (testing "can write and read object"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc "test.edn"]
        (is (cs/ends-with? (sut/write-obj s loc obj) loc))
        (is (= obj (sut/read-obj s loc))))))

  (testing "writes object to file"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc "test.edn"]
        (is (cs/ends-with? (sut/write-obj s loc obj) loc))
        (is (true? (sut/obj-exists? s loc))))))

  (testing "can write to subdirectories"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc "subdir/test.edn"]
        (is (cs/ends-with? (sut/write-obj s loc obj) loc))
        (is (= obj (sut/read-obj s loc))))))

  (testing "`obj-exists?` returns false if file does not exist"
    (is (false? (-> (sut/make-file-storage "test-dir")
                    (sut/obj-exists? "test-loc")))))

  (testing "read returns `nil` if object does not exist"
    (is (nil? (-> (sut/make-file-storage "nonexisting")
                  (sut/read-obj "nonexisting-loc")))))

  (testing "delete-obj"
    (testing "can delete file"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              obj {:key "value"}
              loc "test.edn"]
          (is (cs/ends-with? (sut/write-obj s loc obj) loc))
          (is (true? (sut/delete-obj s loc)))
          (is (false? (sut/obj-exists? s loc))))))

    (testing "false if file does not exist"
      (h/with-tmp-dir dir 
        (is (false? (-> (sut/make-file-storage dir)
                        (sut/delete-obj "test-loc"))))))))

