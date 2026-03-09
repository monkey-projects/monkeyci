(ns monkey.ci.e2e.org-tokens-test
  "Basic flow tests"
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.e2e.common :as c]
            [monkey.ci.time :as t]))

(deftest org-tokens
  (let [u (c/create-user)]
    (let [org {:name (str "test-" (t/now))}
          token (c/user-token u)
          request (fn [method path & [body]]
                    (cond-> (-> (c/request method path)
                                (c/set-token token)
                                (c/accept-edn))
                      body (c/set-body body)
                      true (-> (http/request)
                               (deref))))
          new-org (-> (request :post "/org" org)
                      (c/verify!)
                      (c/try-parse-body))
          reply (request :post (format "/org/%s/token" (:id new-org))
                         {:description "test token"})]
      
      (testing "can create api token"
        (is (= 201 (:status reply))))

      (let [new-key (c/try-parse-body reply)]
        (is (string? (:id new-key)))
        
        (testing "provides token value"
          (is (string? (:token new-key))))

        (testing "token can access org"
          (is (= 200
                 (-> (c/request :get (str "/org/" (:id new-org)))
                     (c/set-api-token (:token new-key))
                     (c/accept-edn)
                     (http/request)
                     (deref)
                     :status))))

        (testing "can delete token"
          (is (= 204 (-> (request :delete (format "/org/%s/token/%s" (:id new-org) (:id new-key)))
                         :status)))))

      (testing "cleanup org"
        (is (true? (c/delete-org new-org)))))

    (testing "cleanup"
      (is (true? (c/delete-user u))))))
