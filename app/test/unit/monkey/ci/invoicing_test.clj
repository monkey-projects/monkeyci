(ns monkey.ci.invoicing-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.invoicing :as sut]
            [monkey.ci.test
             [aleph-test :as at]
             [helpers :as h]]))

(deftest list-customers
  (let [client (sut/make-client {:url "http://test-invoice"})]
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
  (let [client (sut/make-client {:url "http://test-invoice"})]
    (at/with-fake-http [{:url "http://test-invoice/customer"
                         :method :post}
                        {:status 200
                         :headers
                         {"Content-Type" "application/json"}}]
      (testing "invokes `POST /customer`"
        (is (= 200 (-> (sut/create-customer client {:name "test customer"})
                       (deref)
                       :status)))))))

(deftest get-customer
  (testing "matches customer by id"
    (let [client (sut/make-client {:url "http://test"})]
      (at/with-fake-http ["http://test/customer"
                          {:status 200
                           :body (h/to-json [{:id 1 :name "customer 1"}
                                             {:id 2 :name "customer 2"}])
                           :headers
                           {"Content-Type" "application/json"}}]
        (is (= "customer 1"
               (-> (sut/get-customer client "1")
                   (deref)
                   :name)))))))

(deftest list-invoices
  (let [client (sut/make-client {:url "http://test-invoice"})]
    (at/with-fake-http [{:method :get
                         :url "http://test-invoice/invoice"}
                        {:status 200
                         :headers
                         {"Content-Type" "application/json"}}]
      (testing "invokes `GET /invoice`"
        (is (= 200 (-> (sut/list-invoices client)
                       (deref)
                       :status)))))))

(deftest create-invoice
  (let [client (sut/make-client {:url "http://test-invoice"})]
    (at/with-fake-http [{:url "http://test-invoice/invoice"
                         :method :post}
                        {:status 200
                         :headers
                         {"Content-Type" "application/json"}}]
      (testing "invokes `POST /invoice`"
        (is (= 200 (-> (sut/create-invoice client {:customer-id 1})
                       (deref)
                       :status)))))))
