(ns monkey.ci.web.oauth2-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.web.oauth2 :as sut]))

(deftest login-handler
  (testing "returns refresh token if provided"
    (h/with-memory-store st
      (let [handler (sut/login-handler
                     (constantly {:status 200
                                  :body {:access-token "test-access-token"
                                         :refresh-token "test-refresh-token"}})
                     (constantly {:id "test-user"
                                  :sid [:github "test-id"]}))
            req (h/->req {:storage st})]
        (is (= "test-refresh-token"
               (-> (handler req)
                   :body
                   :refresh-token)))))))
