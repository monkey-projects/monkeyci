(ns monkey.ci.params-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [params :as sut]
             [protocols :as p]]
            [monkey.ci.test.aleph-test :as ah]))

(deftest cli-build-params
  (testing "sends request to global api with api token"
    (let [r (sut/->CliBuildParams {:url "http://test"
                                   :token "test-token"})
          params {"key" "value"}]
      (ah/with-fake-http [{:url "http://test/org/test-org/repo/test-repo/param"
                           :method :get}
                          {:status 200
                           :body params}]
        (is (= params @(p/get-build-params r {:org-id "test-org"
                                              :repo-id "test-repo"})))))))

(deftest multi-build-params
  (testing "combines multiple sources"
    (let [s1 (sut/->FixedBuildParams [{:name "first" :value "first value"}
                                      {:name "second" :value "second value"}])
          s2 (sut/->FixedBuildParams [{:name "third" :value "third value"}
                                      {:name "second" :value "updated value"}])
          v (sut/->MultiBuildParams [s1 s2])]
      (is (= [{:name "first" :value "first value"}
              {:name "second" :value "second value"}
              {:name "third" :value "third value"}
              {:name "second" :value "updated value"}]
             @(p/get-build-params v {}))))))
