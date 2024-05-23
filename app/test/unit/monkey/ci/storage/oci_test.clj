(ns monkey.ci.storage.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.keys.pem :as pem]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci
             [protocols :as p]
             [spec :as s]
             [storage :as st]
             [utils :refer [load-privkey]]]
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
                                  :region "test-region"}}))))

  (testing "merges credentials in config"
    (let [creds {:tenancy-ocid "test-tenancy"
                 :user-ocid "test-user"}]
      (is (= creds (-> (st/make-storage {:storage
                                         {:type :oci
                                          :region "test-region"
                                          :credentials creds}})
                       (.conf)
                       (select-keys (keys creds)))))))

  (testing "parses private key file"
    (let [creds {:tenancy-ocid "test-tenancy"
                 :user-ocid "test-user"
                 :private-key "dev-resources/test/test-key.pem"}]
      (is (private-key? (-> (st/make-storage {:storage
                                              {:type :oci
                                               :region "test-region"
                                               :credentials creds}})
                            (.conf)
                            :private-key))))))

(defn- load-test-config []  
  (with-open [r (PushbackReader. (io/reader "dev-resources/test/config.edn"))]
    (-> (edn/read r)
        (update-in [:storage :credentials :private-key] load-privkey))))

(deftest ^:integration oci-integration
  ;; Run some integration tests on an OCI bucket
  (let [conf (load-test-config)
        s (st/make-storage conf)]

    (testing "config is valid"
      (is (spec/valid? :conf/storage (:storage conf))
          (spec/explain-str :conf/storage (:storage conf))))    

    (testing "customer operations"
      (let [id (st/new-id)
            cust {:id id
                  :name "Test customer"}]
        (is (nil? (st/find-customer s id)))
        (is (st/sid? (st/save-customer s cust)))
        (is (= cust (st/find-customer s id)))
        (is (true? (p/delete-obj s (st/customer-sid id))))))))
