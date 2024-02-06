(ns monkey.ci.storage.file-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci.storage :as st]
            [monkey.ci.storage.file :as sut]
            [monkey.ci.helpers :as h]))

(deftest file-storage
  (testing "can write and read object"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["test"]]
        (is (= (st/write-obj s loc obj) loc))
        (is (= obj (st/read-obj s loc))))))

  (testing "writes object to file"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["test"]]
        (is (= (st/write-obj s loc obj) loc))
        (is (true? (st/obj-exists? s loc))))))

  (testing "can write to subdirectories"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["subdir" "test"]]
        (is (= (st/write-obj s loc obj) loc))
        (is (= obj (st/read-obj s loc))))))

  (testing "`obj-exists?` returns false if file does not exist"
    (is (false? (-> (sut/make-file-storage "test-dir")
                    (st/obj-exists? ["test-loc"])))))

  (testing "read returns `nil` if object does not exist"
    (is (nil? (-> (sut/make-file-storage "nonexisting")
                  (st/read-obj ["nonexisting-loc"])))))

  (testing "delete-obj"
    (testing "can delete file"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              obj {:key "value"}
              loc ["test.edn"]]
          (is (= (st/write-obj s loc obj) loc))
          (is (true? (st/delete-obj s loc)))
          (is (false? (st/obj-exists? s loc))))))

    (testing "false if file does not exist"
      (h/with-tmp-dir dir 
        (is (false? (-> (sut/make-file-storage dir)
                        (st/delete-obj ["test-loc"])))))))

  (testing "list-obj"
    (testing "lists directories at location"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              sid ["root" "a" "b"]
              obj {:key "value"}]
          (is (= sid (st/write-obj s sid obj)))
          (is (= ["a"] (st/list-obj s ["root"]))))))
    
    (testing "lists files at location without extension"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              sid ["root" "child"]
              obj {:key "value"}]
          (is (= sid (st/write-obj s sid obj)))
          (is (= ["child"] (st/list-obj s ["root"]))))))))
