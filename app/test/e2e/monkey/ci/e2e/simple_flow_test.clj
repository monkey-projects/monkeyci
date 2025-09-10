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
          reply (-> (c/request :post "/org")
                       (c/set-token token)
                       (c/accept-edn)
                       (c/set-body org)
                       (http/request)
                       (deref))]
      
      (testing "can create org"
        (is (= 201 (:status reply))))

      (let [org (c/try-parse-body reply)]
        (testing "can delete org"
          (is (= 204 (-> (c/request :delete (str "/org/" (:id org)))
                         (c/set-token token)
                         (http/request)
                         (deref)
                         :status))))))

    (testing "cleanup"
      (is (true? (c/delete-user u))))))
