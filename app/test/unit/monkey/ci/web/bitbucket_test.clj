(ns unit.monkey.ci.web.bitbucket-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.bitbucket :as sut]
            [monkey.ci.test.aleph-test :as at]))

(deftest login
  (testing "when token request failes, returns 400 status"
    (at/with-fake-http ["https://bitbucket.org/site/oauth2/access_token"
                        {:status 401}]
      (is (= 400 (-> {:parameters
                      {:query
                       {:code "test-code"}}}
                     (sut/login)
                     :status))))))
