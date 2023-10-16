(ns monkey.ci.test.web.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.web.api :as sut]))

(defn- ->req [ctx]
  {:reitit.core/match
   {:data
    {:monkey.ci.web.common/context ctx}}})

(defn- with-path-param [r k v]
  (assoc-in r [:parameters :path k] v))

(defn- with-path-params [r p]
  (update-in r [:parameters :path] merge p))

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

(deftest get-params
  (testing "merges with higher levels"
    (let [{s :storage :as ctx} (test-ctx)
          [cid pid] (repeatedly st/new-id)]
      (is (some? (st/save-params s [cid] [{:name "param-1" :value "value 1"}])))
      (is (some? (st/save-params s [cid pid] [{:name "param-2" :value "value 2"}])))
      (is (= ["param-1" "param-2"]
             (->> (-> ctx
                      (->req)
                      (with-path-params {:customer-id cid
                                         :project-id pid})
                      (sut/get-params)
                      :body)
                  (map :name)
                  (sort))))))

  (testing "gives priority to lower levels"
    (let [{s :storage :as ctx} (test-ctx)
          [cid pid rid] (repeatedly st/new-id)]
      (is (some? (st/save-params s [cid] [{:name "param-1" :value "customer value"}])))
      (is (some? (st/save-params s [cid pid rid] [{:name "param-1" :value "repo value"}])))
      (let [r (-> ctx
                  (->req)
                  (with-path-params {:customer-id cid
                                     :project-id pid
                                     :repo-id rid})
                  (sut/get-params)
                  :body)]
        (is (= "repo value"
               (-> (zipmap (map :name r) (map :value r))
                   (get "param-1")))))))

  (testing "empty vector if no params"
    (is (= [] (-> (test-ctx)
                  (->req)
                  (with-path-params {:customer-id (st/new-id)})
                  (sut/get-params)
                  :body)))))

(deftest create-webhook
  (testing "assigns secret key"
    (let [{st :storage :as ctx} (test-ctx)
          r (-> ctx
                (->req)
                (with-body {:customer-id "test-customer"})
                (sut/create-webhook))]
      (is (= 201 (:status r)))
      (is (string? (get-in r [:body :secret-key]))))))

(deftest get-webhook
  (testing "does not return the secret key"
    (let [{st :storage :as ctx} (test-ctx)
          wh {:id (st/new-id)
              :secret-key "very secret key"}
          _ (st/save-webhook-details st wh)
          r (-> ctx
                (->req)
                (with-path-param :webhook-id (:id wh))
                (sut/get-webhook))]
      (is (= 200 (:status r)))
      (is (nil? (get-in r [:body :secret-key]))))))
