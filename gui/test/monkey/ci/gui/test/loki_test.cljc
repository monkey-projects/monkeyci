(ns monkey.ci.gui.test.loki-test
  (:require #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer-macros [deftest testing is]])
            [monkey.ci.gui.loki :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest job-query
  (testing "returns query string"
    (is (= "{repo_id=\"test-repo\",build_id=\"test-build\",job_id=\"test-job\"}"
           (sut/job-query ["test-cust" "test-repo" "test-build"] "test-job")))))

(deftest job-logs-request
  (testing "returns log query request map"
    (testing "for completed job"
      (let [sid ["test-cust" "test-repo" "test-build"]
            job {:id "test-job"
                 :start-time 10000
                 :end-time 20000}
            req (sut/job-logs-request sid job)]
        (is (map? req))

        (testing "invokes query range"
          (is (= (str sut/loki-url "/query_range")
                 (:uri req))))

        (testing "has query"
          (is (string? (get-in req [:params :query]))))

        (testing "has start and end time in epoch seconds"
          (is (= 10 (get-in req [:params :start])))
          (is (= 21 (get-in req [:params :end]))))))))
