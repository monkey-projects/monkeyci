(ns monkey.ci.blob.minio-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.blob.minio :as sut]))

(deftest make-client
  (testing "creates minio client"
    (is (some? (sut/make-client "http://test" "test-user" "test-pass")))))

;; TODO Integration tests for minio code
