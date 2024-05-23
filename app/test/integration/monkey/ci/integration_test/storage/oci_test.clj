(ns monkey.ci.integration-test.storage.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [monkey.ci
             [protocols :as p]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.storage.oci :as sut])
  (:import java.io.PushbackReader))

(defn- load-test-config []  
  (with-open [r (PushbackReader. (io/reader "dev-resources/test/config.edn"))]
    (-> (edn/read r)
        (update-in [:storage :credentials :private-key] u/load-privkey))))

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
