(ns monkey.ci.logging.loki-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [monkey.ci.helpers :as h]
            [monkey.ci.logging.loki :as sut]
            [monkey.ci.logging :as l])
  (:import [java.io PipedReader PipedWriter PrintWriter]))

(deftest post-logs
  (testing "sends request"
    (with-redefs [http/request (fn [req]
                                 (md/success-deferred {:status 200
                                                       :body {:request req}}))]
      (let [r (sut/post-logs {:url "http://loki"
                              :tenant-id "test-tenant"
                              :token "test-token"}
                             [{:stream
                               {:label "value"}
                               :values
                               [[100 "Line 1"]
                                [200 "Line 2"]]}])]
        (is (some? r))
        (is (= 200 (:status @r)))

        (testing "to configured loki endpoint"
          (is (= "http://loki" (get-in @r [:body :request :url]))))

        (testing "converts millis to nanos"
          (let [rb (-> (get-in @r [:body :request :body])
                       (h/parse-json))]
            (is (= "100000000" (-> rb
                                   :streams
                                   first
                                   :values
                                   ffirst))
                "converts millis to nanos as string")))

        (testing "sets tenant id in header"
          (is (= "test-tenant" (get-in @r [:body :request :headers "X-Scope-OrgID"]))))

        (testing "sets bearer token in header"
          (is (= "Bearer test-token" (get-in @r [:body :request :headers "Authorization"]))))))))

(deftest stream-to-loki
  (testing "posts all entries to loki asynchronously"
    (let [inv (atom [])
          reader (PipedReader.)
          writer (PipedWriter. reader)
          pw (PrintWriter. writer)]
      (with-redefs [sut/post-logs (fn [_ streams]
                                    (swap! inv conj streams))]
        (let [r (sut/stream-to-loki reader {})]
          (is (md/deferred? r))
          (is (nil? (.close reader)))
          (is (nil? (.close writer)))
          (is (= 0 (:line-count @r))))))))
