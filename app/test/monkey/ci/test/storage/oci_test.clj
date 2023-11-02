(ns monkey.ci.test.storage.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.keys.pem :as pem]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci
             [spec :as s]
             [storage :as st]]
            [monkey.ci.storage.oci :as sut]
            [monkey.oci.os.core :as os])
  (:import java.io.PushbackReader))

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
              (is (= sid (st/write-obj s sid {:key "value"})))
              (is (= 1 (count @writes)))
              (let [w (first @writes)]
                (is (= "test-ns" (:ns w)))
                (is (string? (:contents w)))
                (is (= "path/to/object.edn" (:object-name w)))))))

        (testing "read gets object from bucket"
          (with-redefs [os/get-object (fn [_ args]
                                        "{:key \"value\"}")
                        os/head-object (constantly true)]
            (is (= {:key "value"} (st/read-obj s sid)))))

        (testing "returns `nil` when object does not exist on read"
          (with-redefs [os/get-object (fn [_ args]
                                        "{:key \"value\"}")
                        os/head-object (constantly false)]
            (is (nil? (st/read-obj s sid))))))

      (testing "`obj-exists?` sends head request"
        (with-redefs [os/head-object (fn [_ {:keys [object-name]}]
                                       (future (= "test.edn" object-name)))]
          (is (true? (st/obj-exists? s ["test"])))
          (is (false? (st/obj-exists? s ["other"])))))

      (testing "can delete object"
        (with-redefs [os/delete-object (fn [_ args]
                                         true)]
          (is (true? (st/delete-obj s sid)))))

      (testing "false when delete fails"
        (with-redefs [os/delete-object (fn [_ args]
                                         (md/error-deferred (ex-info "Object not found" {})))]
          (is (false? (st/delete-obj s sid))))))))

(deftest make-storage
  (testing "can make oci storage"
    (is (some? (st/make-storage {:type :oci
                                 :region "test-region"}))))

  (testing "merges credentials in config"
    (let [creds {:tenancy-ocid "test-tenancy"
                 :user-ocid "test-user"}]
      (is (= creds (-> (st/make-storage {:type :oci
                                         :region "test-region"
                                         :credentials creds})
                       (.conf)
                       (select-keys (keys creds))))))))

(defn- load-privkey
  "Load private key from file"
  [f]
  (with-open [r (io/reader f)]
    (pem/read-privkey r nil)))

(defn- load-test-config []  
  (with-open [r (PushbackReader. (io/reader "dev-resources/test/config.edn"))]
    (-> (edn/read r)
        (update-in [:storage :credentials :private-key] load-privkey))))

(deftest ^:integration oci-integration
  ;; Run some integration tests on an OCI bucket
  (let [conf (:storage (load-test-config))
        s (st/make-storage conf)]

    (testing "config is valid"
      (is (spec/valid? :conf/storage conf) (spec/explain-str :conf/storage conf)))    

    (testing "customer operations"
      (let [id (st/new-id)
            cust {:id id
                  :name "Test customer"}]
        (is (nil? (st/find-customer s id)))
        (is (st/sid? (st/save-customer s cust)))
        (is (= cust (st/find-customer s id)))
        (is (true? (st/delete-obj s (st/customer-sid id))))))))
