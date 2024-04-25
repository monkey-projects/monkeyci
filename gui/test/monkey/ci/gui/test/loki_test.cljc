(ns monkey.ci.gui.test.loki-test
  (:require #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer-macros [deftest testing is]])
            [monkey.ci.gui.loki :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest job-query
  (testing "creates query map"
    (is (= {"repo_id" "test-repo"
            "build_id" "test-build"
            "job_id" "test-job"}
           (sut/job-query ["test-cust" "test-repo" "test-build"] "test-job")))))

(deftest query->str
  (testing "returns query string"
    (is (= "{repo_id=\"test-repo\",build_id=\"test-build\",job_id=\"test-job\"}"
           (sut/query->str
            (sut/job-query ["test-cust" "test-repo" "test-build"] "test-job"))))))

(deftest job-request
  (testing "returns log request map for path"
    (testing "for completed job"
      (let [sid ["test-cust" "test-repo" "test-build"]
            job {:id "test-job"
                 :start-time 10000
                 :end-time 20000}
            req (sut/job-request "/query_range" sid job)]
        (is (map? req))

        (testing "invokes query range"
          (is (= (str sut/loki-url "/query_range")
                 (:uri req))))

        (testing "has query"
          (is (string? (get-in req [:params :query]))))

        (testing "has start and end time in epoch seconds"
          (is (= 10 (get-in req [:params :start])))
          (is (= 21 (get-in req [:params :end]))))))

    (testing "for started job"
      (let [sid ["test-cust" "test-repo" "test-build"]
            job {:id "test-job"
                 :start-time 10000}
            req (sut/job-request "/query_range" sid job)]
        (is (map? req))

        (testing "invokes query range"
          (is (= (str sut/loki-url "/query_range")
                 (:uri req))))

        (testing "has query"
          (is (string? (get-in req [:params :query]))))

        (testing "has start time in epoch seconds"
          (is (= 10 (get-in req [:params :start])))
          (is (nil? (get-in req [:params :end])))))))

  (testing "sets `x-scope-orgid` header"
    (is (= "test-cust"
           (-> ["test-cust" "test-repo" "test-build"]
               (sut/job-request {})
               :headers
               (get "X-Scope-OrgID"))))))

(deftest label-values-request
  (testing "creates request with label path"
    (let [req (sut/label-values-request "test-label"
                                        ["cust" "repo" "build"]
                                        {:id "test-job"})]
      (is (re-matches #"^.*/label/test-label/values$"
                      (:uri req))))))
