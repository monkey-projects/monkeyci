(ns monkey.ci.storage.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.keys.pem :as pem]
            [manifold.deferred :as md]
            [monkey.ci
             [protocols :as p]
             [spec :as s]
             [storage :as st]]
            [monkey.ci.storage.oci :as sut]
            [monkey.oci.os.core :as os]))

(deftest oci-object-storage
  (let [conf {:compartment-id "test-compartment"
              :region "test-region"}
        s (sut/make-oci-storage conf)
        sid ["path" "to" "object"]]

    (with-redefs [os/get-namespace (constantly (future "test-ns"))]

      (testing "creates client"
        (is (some? (.client s))))
      
      (testing "read and write objects"      
        
        (testing "write puts object to bucket as edn"
          (let [writes (atom [])]
            (with-redefs [os/put-object (fn [_ args]
                                          (swap! writes conj args))]
              (is (= sid (p/write-obj s sid {:key "value"})))
              (is (= 1 (count @writes)))
              (let [w (first @writes)]
                (is (= "test-ns" (:ns w)))
                (is (string? (:contents w)))
                (is (= "path/to/object.edn" (:object-name w)))))))

        (testing "read gets object from bucket"
          (with-redefs [os/get-object (fn [_ args]
                                        "{:key \"value\"}")
                        os/head-object (constantly true)]
            (is (= {:key "value"} (p/read-obj s sid)))))

        (testing "returns `nil` when object does not exist on read"
          (with-redefs [os/get-object (fn [_ args]
                                        "{:key \"value\"}")
                        os/head-object (constantly false)]
            (is (nil? (p/read-obj s sid))))))

      (testing "`obj-exists?` sends head request"
        (with-redefs [os/head-object (fn [_ {:keys [object-name]}]
                                       (future (= "test.edn" object-name)))]
          (is (true? (p/obj-exists? s ["test"])))
          (is (false? (p/obj-exists? s ["other"])))))

      (testing "can delete object"
        (with-redefs [os/delete-object (fn [_ args]
                                         true)]
          (is (true? (p/delete-obj s sid)))))

      (testing "false when delete fails"
        (with-redefs [os/delete-object (fn [_ args]
                                         (md/error-deferred (ex-info "Object not found" {})))]
          (is (false? (p/delete-obj s sid)))))

      (testing "list objects"
        (testing "passes sid prefix"
          (let [inv (atom nil)]
            (with-redefs [os/list-objects (fn [_ args]
                                            (reset! inv args)
                                            (md/success-deferred
                                             {:objects []
                                              :prefixes []}))]
              (is (some? (p/list-obj s sid)))
              (is (= "path/to/object/" (:prefix @inv))))))

        (testing "returns files without extensions"
          (with-redefs [os/list-objects (constantly
                                         (md/success-deferred
                                          {:objects [{:name "prefix/test.edn"}]}))]
            (is (= ["test"] (p/list-obj s ["prefix"])))))

        (testing "returns dirs without prefix"
          (with-redefs [os/list-objects (constantly
                                         (md/success-deferred
                                          {:prefixes ["prefix/test/"]}))]
            (is (= ["test"] (p/list-obj s ["prefix"])))))))))

(def private-key? (partial instance? java.security.PrivateKey))

(deftest make-storage
  (testing "can make oci storage"
    (is (some? (st/make-storage {:storage
                                 {:type :oci
                                  :region "test-region"}})))))

