(ns monkey.ci.web.common-test
  (:require [clj-commons.byte-streams :as bs]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.web.common :as sut]))

(deftest parse-body
  (testing "parses string body according to content type"
    (is (= {:test-key "test value"}
           (-> {:body "{\"test_key\": \"test value\"}"
                :headers {"Content-Type" "application/json"}}
               (sut/parse-body)
               :body))))

  (testing "parses input stream body according to content type"
    (is (= {:test-key "test value"}
           (-> {:body (bs/to-input-stream (.getBytes "{\"test_key\": \"test value\"}"))
                :headers {"Content-Type" "application/json"}}
               (sut/parse-body)
               :body)))))

(deftest req->ext-uri
  (testing "determines external uri using host, scheme and path"
    (is (= "http://test:1234/v1"
           (sut/req->ext-uri
            {:scheme :http
             :uri "/v1/org/test-cust"
             :headers {"host" "test:1234"}}
            "/org")))))

