(ns monkey.ci.invoicing-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.invoicing :as sut]
            [monkey.ci.test.aleph-test :as at]))

(deftest list-customers
  (let [client {:url "http://test-invoice"}]
    (at/with-fake-http [{:method :get
                         :url "http://test-invoice/customer"}
                        {:status 200
                         :headers
                         {"Content-Type" "application/json"}}]
      (testing "invokes `GET /customer`"
        (is (= 200 (-> (sut/list-customers client)
                       (deref)
                       :status)))))))

(deftest create-customer
  (let [client {:url "http://test-invoice"}]
    (at/with-fake-http [{:url "http://test-invoice/customer"
                         :method :post}
                        {:status 200
                         :headers
                         {"Content-Type" "application/json"}}]
      (testing "invokes `POST /customer`"
        (is (= 200 (-> (sut/create-customer client {:name "test customer"})
                       (deref)
                       :status)))))))
