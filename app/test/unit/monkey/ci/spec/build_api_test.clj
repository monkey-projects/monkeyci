(ns monkey.ci.spec.build-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.build-api :as sut]))

(deftest api-spec
  (testing "valid for url config"
    (is (s/valid? ::sut/api {:token "test-token"
                             :url "http://test-url"})))

  (testing "valid for port config"
    (is (s/valid? ::sut/api {:token "test-token"
                             :port 12342})))

  (testing "invalid if neither port nor url provided"
    (is (not (s/valid? ::sut/api {:token "test-token"})))))
