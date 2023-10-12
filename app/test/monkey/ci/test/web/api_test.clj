(ns monkey.ci.test.web.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.web.api :as sut]))

(defn- ->req [ctx]
  {:reitit.core/match
   {:data
    {:monkey.ci.web.handler/context ctx}}})

(defn- with-path-param [r k v]
  (assoc-in r [:parameters :path k] v))

(defn- with-body [r v]
  (assoc-in r [:parameters :body] v))

(defn- test-ctx []
  {:storage (st/make-memory-storage)})

(deftest get-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as ctx} (test-ctx)
          req (-> ctx
                  (->req)
                  (with-path-param :customer-id (:id cust)))]
      (is (st/sid? (st/save-customer st cust)))
      (is (= cust (:body (sut/get-customer req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (test-ctx)
                   (->req)
                   (with-path-param :customer-id "nonexisting")
                   (sut/get-customer)
                   :status)))))

(deftest create-customer
  (testing "returns created customer with id"
    (let [r (-> (test-ctx)
                (->req)
                (with-body {:name "new customer"})
                (sut/create-customer)
                :body)]
      (is (= "new customer" (:name r)))
      (is (string? (:id r))))))

(deftest update-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as ctx} (test-ctx)
          req (-> ctx
                  (->req)
                  (with-path-param :customer-id (:id cust))
                  (with-body {:name "updated"}))]
      (is (st/sid? (st/save-customer st cust)))
      (is (= {:id (:id cust)
              :name "updated"}
             (:body (sut/update-customer req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (test-ctx)
                   (->req)
                   (with-path-param :customer-id "nonexisting")
                   (sut/update-customer)
                   :status)))))
