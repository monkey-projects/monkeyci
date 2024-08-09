(ns monkey.ci.web.api.params-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.api.params :as sut]
            [monkey.ci.storage :as st]
            [monkey.ci.helpers :as h]))

(deftest get-customer-params
  (testing "empty vector if no params"
    (is (= [] (-> (h/test-rt)
                  (h/->req)
                  (h/with-path-params {:customer-id (st/new-id)})
                  (sut/get-customer-params)
                  :body))))

  (testing "returns stored parameters"
    (let [{st :storage :as rt} (h/test-rt)
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
                 :body))))))

(deftest get-repo-params
  (let [{st :storage :as rt} (h/test-rt)
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

