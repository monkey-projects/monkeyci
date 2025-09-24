(ns monkey.ci.params-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [params :as sut]
             [protocols :as p]]
            [monkey.ci.test.aleph-test :as ah]))

(deftest cli-build-params
  (testing "sends request to global api with api token"
    (let [r (sut/->CliBuildParams {:api "http://test"
                                   :api-key "test-token"})
          params {"key" "value"}]
      (ah/with-fake-http [{:url "http://test/org/test-org/repo/test-repo/param"
                           :method :get}
                          {:status 200
                           :body params}]
        (is (= params @(p/get-build-params r {:org-id "test-org"
                                              :repo-id "test-repo"})))))))
