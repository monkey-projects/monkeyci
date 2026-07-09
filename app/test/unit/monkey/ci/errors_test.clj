(ns monkey.ci.errors-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.errors :as sut]))

(deftest ->error
  (testing "creates error from ex-info"
    (let [err (sut/->error (ex-info "Test error" {:key "value"}))]
      (is (= "Test error" (sut/error-msg err)))
      (is (= "value" (:key (sut/error-props err)))))))

(deftest unwrap-exception
  (testing "when no cause, returns error"
    (let [ex (ex-info "test error" {})]
      (is (= ex (sut/unwrap-exception ex)))))

  (testing "returns cause"
    (let [c (ex-info "cause" {})
          ex (ex-info "test error" {} c)]
      (is (= c (sut/unwrap-exception ex)))))

  (testing "recursively unwraps cause"
    (let [c (ex-info "cause" {})
          a (ex-info "intermediate" {} c)
          ex (ex-info "test error" {} a)]
      (is (= c (sut/unwrap-exception ex))))))
