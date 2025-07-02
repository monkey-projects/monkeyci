(ns monkey.ci.web.api.params-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.storage :as st]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.params :as sut]))

(deftest get-org-params
  (testing "empty vector if no params"
    (is (= [] (-> (trt/test-runtime)
                  (h/->req)
                  (h/with-path-params {:org-id (st/new-id)})
                  (sut/get-org-params)
                  :body))))

  (testing "decrypts stored parameters"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-decrypter (constantly "decrypted")))
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
                 :value))))))

(deftest get-repo-params
  (let [{st :storage :as rt} (-> (trt/test-runtime)
                                 (trt/set-decrypter (fn [x _] x)))
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

    (testing "matches all params when multiple labels"
      (let [params [{:parameters
                     [{:name "param-1"
                       :value "value-1"}]
                     :label-filters
                     [[{:label "test-label"
                        :value "value-1"}]]}
                    {:parameters
                     [{:name "param-2"
                       :value "value-2"}]
                     :label-filters
                     [[{:label "test-label"
                        :value "value-2"}]]}]]

        (is (some? (st/save-repo st
                                 {:id repo-id
                                  :org-id org-id
                                  :name "test repo"
                                  :labels [{:name "test-label"
                                            :value "value-1"}
                                           {:name "test-label"
                                            :value "value-2"}]})))
        (is (some? (st/save-params st org-id params)))

        (is (= [{:name "param-1" :value "value-1"}
                {:name "param-2" :value "value-2"}]
               (-> rt
                   (h/->req)
                   (h/with-path-params {:org-id org-id
                                        :repo-id repo-id})
                   (sut/get-repo-params)
                   :body)))))

    (testing "decrypts parameters"
      (let [repo (h/gen-repo)
            org (-> (h/gen-org)
                    (assoc :repos {(:id repo) repo}))
            org-id (:id org)
            param {:parameters
                   [{:name "test-param"
                     :value "test-value"}]}
            _ (st/save-org st org)
            _ (st/save-params st org-id [param])
            res (-> rt
                    (trt/set-decrypter (constantly "decrypted"))
                    (h/->req)
                    (h/with-path-params {:org-id org-id
                                         :repo-id (:id repo)})
                    (sut/get-repo-params))]
        (is (= [{:name "test-param"
                 :value "decrypted"}]
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
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-decrypter (constantly "decrypted")))
          org-id "test-org"
          params {:id (st/new-id)
                  :parameters
                  [{:name "test-param"
                    :value "test-val"}]}
          _ (st/save-params st org-id [params])
          req (-> rt
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:org-id org-id
                           :param-id (:id params)}}))]
      (is (= "decrypted"
             (-> (sut/get-param req)
                 :body
                 :parameters
                 first
                 :value))))))

(deftest create-param
  (testing "encrypts values"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-encrypter (fn [_ cuid]
                                                        (str "encrypted with " cuid))))
          org-id "test-org"
          req (-> rt
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:org-id org-id}
                          :body
                          {:parameters [{:name "test-param"
                                         :value "test-val"}]}}))
          resp (sut/create-param req)]
      (is (some? resp))
      (is (= (str "encrypted with " (-> resp :body :id))
             (-> (st/find-params st org-id)
                 first
                 :parameters
                 first
                 :value))))))

(deftest update-params
  (testing "encrypts values"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-encrypter (fn [_ cuid]
                                                        (str "encrypted with " cuid))))
          id "test-params-id"
          req (-> rt
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:org-id "test-org"}
                          :body
                          [{:id id
                            :parameters [{:name "test-param"
                                          :value "test-val"}]}]}))]
      (is (some? (sut/update-params req)))
      (is (= (str "encrypted with " id)
             (-> (st/find-params st "test-org")
                 first
                 :parameters
                 first
                 :value))))))
