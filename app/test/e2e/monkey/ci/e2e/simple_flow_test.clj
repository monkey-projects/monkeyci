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

        (testing "repo"
          (let [repo {:org-id (:id org)
                      :name "test repo"
                      :url "http://github.com/test"
                      :main-branch "main"}
                path (str "/org/" (:id org) "/repo")
                reply (request :post path repo)
                repo-id (:id (c/try-parse-body reply))]
            (testing "can create"
              (is (= 201 (:status reply)))
              (is (string? repo-id)))

            (testing "can update"
              (is (= 200 (-> (request :put (str path "/" repo-id)
                                      (assoc repo :github-id 1234))
                             :status))))

            (testing "can create with github id"
              (is (= 201 (->> {:org-id (:id org)
                               :name "other repo"
                               :url "http://github.com/other"
                               :github-id 5678}
                              (request :post path)
                              :status))))))
        
        (testing "can delete org"
          (is (= 204 (-> (request :delete (str "/org/" (:id org)))
                         :status))))))

    (testing "cleanup"
      (is (true? (c/delete-user u))))))
