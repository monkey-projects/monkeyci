(ns monkey.ci.e2e.simple-flow-test
  "Basic flow tests"
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.e2e.common :as c]
            [monkey.ci.time :as t]))

(deftest new-user
  (let [u (c/create-user)]
    (testing "valid user has been created"
      (is (some? u)))

    (let [org {:name (str "test-" (t/now))}
          token (c/user-token u)
          request (fn [method path & [body]]
                    (cond-> (-> (c/request method path)
                                (c/set-token token)
                                (c/accept-edn))
                      body (c/set-body body)
                      true (-> (http/request)
                               (deref))))
          reply (request :post "/org" org)]
      
      (testing "can create org"
        (is (= 201 (:status reply))))

      (let [org (c/try-parse-body reply)]
        (is (string? (:id org)))
        
        (testing "org is linked to user"
          (is (= [(:id org)]
                 (-> (request :get (format "/user/%s/%s" (:type u) (:type-id u)))
                     (c/try-parse-body)
                     :orgs))))

        (testing "can delete org"
          (is (= 204 (-> (request :delete (str "/org/" (:id org)))
                         :status))))))

    (testing "cleanup"
      (is (true? (c/delete-user u))))))
