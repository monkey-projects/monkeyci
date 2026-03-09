(ns monkey.ci.web.oauth2-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.test
             [aleph-test :as af]
             [helpers :as h]
             [runtime :as trt]]
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

(deftest refresh-token
  (testing "posts to configured url with refresh token"
    (af/with-fake-http [{:url "https://test.com/login/oauth/access_token"
                         :request-method :post}
                        (fn [req]
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (h/to-json {:access-token "new-token"
                                             :refresh-token "new-refresh-token"})})]
      (is (= {:access-token "new-token"
              :refresh-token "new-refresh-token"}
             (-> (trt/test-runtime)
                 (h/->req)
                 (assoc-in [:parameters :body :refresh-token] "old-refresh-token")
                 (as-> r (sut/refresh-token
                          {:get-creds (constantly {:client-id "test-client-id"
                                                   :client-secret "test-client-secret"})
                           :request-token-url "https://test.com/login/oauth/access_token"
                           :set-params (fn [r _] r)}
                          r))
                 :body))))))
