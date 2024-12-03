(ns monkey.ci.web.api.params-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.api.params :as sut]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(defrecord TestVault []
  p/Vault
  (encrypt [_ _]
    "encrypted")
  (decrypt [_ _]
    "decrypted"))

(def test-vault (->TestVault))

(deftest get-customer-params
  (testing "empty vector if no params"
    (is (= [] (-> (trt/test-runtime)
                  (h/->req)
                  (h/with-path-params {:customer-id (st/new-id)})
                  (sut/get-customer-params)
                  :body))))

  (testing "returns stored parameters"
    (let [{st :storage :as rt} (trt/test-runtime)
          cust-id (st/new-id)
          params [{:parameters [{:name "test-param"
                                 :value "test-value"}]
                   :label-filters [[{:label "test-label"
                                     :value "test-value"}]]}]
          _ (st/save-params st cust-id params)]
      (is (= params
             (-> rt
                 (h/->req)
                 (h/with-path-params {:customer-id cust-id})
                 (sut/get-customer-params)
                 :body)))))

  (testing "decrypts parameters"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault test-vault))
          cust-id (st/new-id)
          params [{:parameters [{:name "test-param"
                                 :value "test-value"}]
                   :label-filters [[{:label "test-label"
                                     :value "test-value"}]]}]
          _ (st/save-params st cust-id params)]
      (is (= "decrypted"
             (-> rt
                 (h/->req)
                 (h/with-path-params {:customer-id cust-id})
                 (sut/get-customer-params)
                 :body
                 first
                 :parameters
                 first
                 :value))))))

(deftest get-repo-params
  (let [{st :storage :as rt} (trt/test-runtime)
        [cust-id repo-id] (repeatedly st/new-id)
        _ (st/save-customer st {:id cust-id
                                :repos {repo-id
                                        {:id repo-id
                                         :name "test repo"
                                         :labels [{:name "test-label"
                                                   :value "test-value"}]}}})]

    (testing "empty list if no params"
      (is (= [] (-> rt
                    (h/->req)
                    (h/with-path-params {:customer-id cust-id
                                       :repo-id repo-id})
                    (sut/get-repo-params)
                    :body))))

    (testing "returns matching parameters according to label filters"
      (let [params [{:parameters [{:name "test-param"
                                   :value "test-value"}]
                     :label-filters [[{:label "test-label"
                                       :value "test-value"}]]}]
            _ (st/save-params st cust-id params)]

        (is (= [{:name "test-param" :value "test-value"}]
               (-> rt
                   (h/->req)
                   (h/with-path-params {:customer-id cust-id
                                      :repo-id repo-id})
                   (sut/get-repo-params)
                   :body)))))

    (testing "returns `404 not found` if repo does not exist"
      (is (= 404
             (-> rt
                 (h/->req)
                 (h/with-path-params {:customer-id cust-id
                                    :repo-id "other-repo"})
                 (sut/get-repo-params)
                 :status))))))

(deftest create-param
  (testing "encrypts values"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault test-vault))
          req (-> rt
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:customer-id "test-cust"}
                          :body
                          {:parameters [{:name "test-param"
                                         :value "test-val"}]}}))]
      (is (some? (sut/create-param req)))
      (is (= "encrypted"
             (-> (st/find-params st "test-cust")
                 first
                 :parameters
                 first
                 :value))))))


