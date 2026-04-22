(ns monkey.ci.errors-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.errors :as sut]))

(deftest ->error
  (testing "creates error from ex-info"
    (let [err (sut/->error (ex-info "Test error" {:key "value"}))]
      (is (= "Test error" (sut/error-msg err)))
      (is (= "value" (:key (sut/error-props err)))))))
