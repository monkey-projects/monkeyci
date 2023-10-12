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
            loc ["test"]]
        (is (= (sut/write-obj s loc obj) loc))
        (is (= obj (sut/read-obj s loc))))))

  (testing "writes object to file"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["test"]]
        (is (= (sut/write-obj s loc obj) loc))
        (is (true? (sut/obj-exists? s loc))))))

  (testing "can write to subdirectories"
    (h/with-tmp-dir dir
      (let [s (sut/make-file-storage dir)
            obj {:key "value"}
            loc ["subdir" "test"]]
        (is (= (sut/write-obj s loc obj) loc))
        (is (= obj (sut/read-obj s loc))))))

  (testing "`obj-exists?` returns false if file does not exist"
    (is (false? (-> (sut/make-file-storage "test-dir")
                    (sut/obj-exists? ["test-loc"])))))

  (testing "read returns `nil` if object does not exist"
    (is (nil? (-> (sut/make-file-storage "nonexisting")
                  (sut/read-obj ["nonexisting-loc"])))))

  (testing "delete-obj"
    (testing "can delete file"
      (h/with-tmp-dir dir
        (let [s (sut/make-file-storage dir)
              obj {:key "value"}
              loc ["test.edn"]]
          (is (= (sut/write-obj s loc obj) loc))
          (is (true? (sut/delete-obj s loc)))
          (is (false? (sut/obj-exists? s loc))))))

    (testing "false if file does not exist"
      (h/with-tmp-dir dir 
        (is (false? (-> (sut/make-file-storage dir)
                        (sut/delete-obj ["test-loc"]))))))))

(deftest webhook-details
  (testing "sid is a vector"
    (is (vector? (sut/webhook-sid "test-id"))))
  
  (testing "can create and find"
    (h/with-memory-store st
      (let [id (str (random-uuid))
            d {:id id}]
        (is (sut/sid? (sut/save-webhook-details st d)))
        (is (= d (sut/find-details-for-webhook st id)))))))

(deftest build-metadata
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (str (random-uuid))
            md {:build-id build-id
                :repo-id "test-repo"
                :project-id "test-project"
                :customer-id "test-cust"}]
        (is (sut/sid? (sut/create-build-metadata st md)))
        (is (= md (sut/find-build-metadata st md)))))))

(deftest build-results
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (str (random-uuid))
            md {:build-id build-id
                :repo-id "test-repo"
                :project-id "test-project"
                :customer-id "test-cust"}]
        (is (sut/sid? (sut/create-build-results st md {:status :success})))
        (is (= :success (:status (sut/find-build-results st md))))))))

(deftest projects
  (testing "stores project in customer"
    (h/with-memory-store st
      (is (sut/sid? (sut/save-project st {:customer-id "test-customer"
                                          :id "test-project"
                                          :name "Test project"})))
      (is (some? (:projects (sut/find-customer st "test-customer"))))))

  (testing "can find project via customer"
    (h/with-memory-store st
      (let [cid "some-customer"
            pid "some-project"
            p {:customer-id cid
               :id pid
               :name "Test project"}]
        (is (sut/sid? (sut/save-project st p)))
        (is (= p (sut/find-project st [cid pid])))))))

(deftest save-build-result
  (testing "writes to build result object"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid ["test-customer" "test-project" "test-repo" "test-build"]
            evt {:type :build/completed
                 :build {:sid sid}
                 :exit 0
                 :result :success}]
        (is (sut/sid? (sut/save-build-result ctx evt)))
        (is (= {:exit 0
                :result :success}
               (sut/find-build-results st sid)))))))
