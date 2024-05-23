(ns monkey.ci.storage.file-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.storage.file :as sut]
            [monkey.ci.helpers :as h]))

(deftest file-storage
  (testing "can write and read object"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["test"]]
        (is (= (p/write-obj s loc obj) loc))
        (is (= obj (p/read-obj s loc))))))

  (testing "writes object to file"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["test"]]
        (is (= (p/write-obj s loc obj) loc))
        (is (true? (p/obj-exists? s loc))))))

  (testing "can write to subdirectories"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["subdir" "test"]]
        (is (= (p/write-obj s loc obj) loc))
        (is (= obj (p/read-obj s loc))))))

  (testing "`obj-exists?` returns false if file does not exist"
    (is (false? (-> (sut/make-file-storage "test-dir")
                    (p/obj-exists? ["test-loc"])))))

  (testing "read returns `nil` if object does not exist"
    (is (nil? (-> (sut/make-file-storage "nonexisting")
                  (p/read-obj ["nonexisting-loc"])))))

  (testing "object with `nil` in path does not exist"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["subdir" "test"]]
        (is (= (p/write-obj s loc obj) loc))
        (is (= obj (p/read-obj s loc)))
        (is (nil? (p/read-obj s (conj loc nil)))))))

  (testing "delete-obj"
    (testing "can delete file"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              obj {:key "value"}
              loc ["test.edn"]]
          (is (= (p/write-obj s loc obj) loc))
          (is (true? (p/delete-obj s loc)))
          (is (false? (p/obj-exists? s loc))))))

    (testing "false if file does not exist"
      (h/with-tmp-dir dir 
        (is (false? (-> (sut/make-file-storage dir)
                        (p/delete-obj ["test-loc"])))))))

  (testing "list-obj"
    (testing "lists directories at location"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              sid ["root" "a" "b"]
              obj {:key "value"}]
          (is (= sid (p/write-obj s sid obj)))
          (is (= ["a"] (p/list-obj s ["root"]))))))
    
    (testing "lists files at location without extension"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              sid ["root" "child"]
              obj {:key "value"}]
          (is (= sid (p/write-obj s sid obj)))
          (is (= ["child"] (p/list-obj s ["root"]))))))))
