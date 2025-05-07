(ns monkey.ci.web.api.params-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [protocols :as p]
             [storage :as st]
             [vault :as v]]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.params :as sut]))

(defrecord TestVault []
  p/Vault
  (encrypt [_ _ _]
    "encrypted")
  (decrypt [_ _ _]
    "decrypted"))

(def test-vault (->TestVault))

(deftest get-org-params
  (testing "empty vector if no params"
    (is (= [] (-> (trt/test-runtime)
                  (h/->req)
                  (h/with-path-params {:org-id (st/new-id)})
                  (sut/get-org-params)
                  :body))))

  (testing "returns stored parameters"
    (let [{st :storage :as rt} (trt/test-runtime)
          org-id (st/new-id)
          params [{:parameters [{:name "test-param"
                                 :value "test-value"}]
                   :label-filters [[{:label "test-label"
                                     :value "test-value"}]]}]
          _ (st/save-params st org-id params)]
      (is (= params
             (-> rt
                 (h/->req)
                 (h/with-path-params {:org-id org-id})
                 (sut/get-org-params)
                 :body)))))

  (testing "decrypts parameters"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault test-vault))
          org-id (st/new-id)
          params [{:parameters [{:name "test-param"
                                 :value "test-value"}]
                   :label-filters [[{:label "test-label"
                                     :value "test-value"}]]}]
          _ (st/save-params st org-id params)]
      (is (= "decrypted"
             (-> rt
                 (h/->req)
                 (h/with-path-params {:org-id org-id})
                 (sut/get-org-params)
                 :body
                 first
                 :parameters
                 first
                 :value)))
      (is (some? (st/find-crypto st org-id))
          "creates new crypto record"))))

(deftest get-repo-params
  (let [{st :storage :as rt} (trt/test-runtime)
        [org-id repo-id] (repeatedly st/new-id)
        _ (st/save-org st {:id org-id
                           :repos {repo-id
                                   {:id repo-id
                                    :name "test repo"
                                    :labels [{:name "test-label"
                                              :value "test-value"}]}}})]

    (testing "empty list if no params"
      (is (= [] (-> rt
                    (h/->req)
                    (h/with-path-params {:org-id org-id
                                         :repo-id repo-id})
                    (sut/get-repo-params)
                    :body))))

    (testing "returns matching parameters according to label filters"
      (let [params [{:parameters [{:name "test-param"
                                   :value "test-value"}]
                     :label-filters [[{:label "test-label"
                                       :value "test-value"}]]}]
            _ (st/save-params st org-id params)]

        (is (= [{:name "test-param" :value "test-value"}]
               (-> rt
                   (h/->req)
                   (h/with-path-params {:org-id org-id
                                        :repo-id repo-id})
                   (sut/get-repo-params)
                   :body)))))

    (testing "decrypts parameters using vault"
      (let [iv (v/generate-iv)
            vault (v/make-fixed-key-vault {})
            repo (h/gen-repo)
            org (-> (h/gen-org)
                    (assoc :repos {(:id repo) repo}))
            org-id (:id org)
            param {:parameters
                   [{:name "test-param"
                     :value (p/encrypt vault iv "test-value")}]}
            _ (st/save-org st org)
            _ (st/save-crypto st {:org-id org-id
                                  :iv iv})
            _ (st/save-params st org-id [param])
            res (-> rt
                    (trt/set-vault vault)
                    (h/->req)
                    (h/with-path-params {:org-id org-id
                                         :repo-id (:id repo)})
                    (sut/get-repo-params))]
        (is (= [{:name "test-param"
                 :value "test-value"}]
               (:body res)))))

    (testing "returns `404 not found` if repo does not exist"
      (is (= 404
             (-> rt
                 (h/->req)
                 (h/with-path-params {:org-id org-id
                                      :repo-id "other-repo"})
                 (sut/get-repo-params)
                 :status))))))

(deftest get-param
  (testing "decrypts values"
    (let [vault (v/make-fixed-key-vault {})
          iv (v/generate-iv)
          {st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault vault))
          org-id "test-org"
          params {:id (st/new-id)
                  :parameters
                  [{:name "test-param"
                    :value (p/encrypt vault iv "test-val")}]}
          _ (st/save-params st org-id [params])
          req (-> rt
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:org-id org-id
                           :param-id (:id params)}}))]
      (is (some? (st/save-crypto st {:org-id org-id
                                     :iv iv})))
      (is (= "test-val"
             (-> (sut/get-param req)
                 :body
                 :parameters
                 first
                 :value))))))

(deftest create-param
  (testing "encrypts values"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault test-vault))
          org-id "test-org"
          req (-> rt
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:org-id org-id}
                          :body
                          {:parameters [{:name "test-param"
                                         :value "test-val"}]}}))]
      (is (some? (sut/create-param req)))
      (is (= "encrypted"
             (-> (st/find-params st org-id)
                 first
                 :parameters
                 first
                 :value)))
      (is (some? (st/find-crypto st org-id))
          "creates new crypto record"))))

(deftest update-params
  (testing "encrypts values"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault test-vault))
          req (-> rt
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:org-id "test-org"}
                          :body
                          [{:parameters [{:name "test-param"
                                          :value "test-val"}]}]}))]
      (is (some? (sut/update-params req)))
      (is (= "encrypted"
             (-> (st/find-params st "test-org")
                 first
                 :parameters
                 first
                 :value))))))
