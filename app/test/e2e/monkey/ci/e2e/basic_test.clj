(ns monkey.ci.e2e.basic-test
  "Basic end-to-end tests that verify connectivity and public endpoints"
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.e2e.common :refer [sut-url]]))

(deftest health
  (testing "/health"
    (is (= 200 (-> (http/get (sut-url "/health"))
                   (deref)
                   :status)))))

(deftest metrics
  (testing "/metrics"
    (let [r (http/get (sut-url "/metrics"))]
      (is (= 200 (:status @r)))
      (is (not-empty (bs/to-string (:body @r)))))))

(deftest version
  (testing "/version"
    (let [r (http/get (sut-url "/version"))]
      (is (= 200 (:status @r)))
      (is (not-empty (bs/to-string (:body @r)))))))
