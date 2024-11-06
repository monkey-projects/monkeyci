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
