(ns monkey.ci.retry-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci.retry :as sut]))

(deftest async-retry
  (testing "no retry if successful"
    (let [inv (atom 0)
          r @(sut/async-retry #(md/success-deferred (swap! inv inc))
                              {:max-retries 10
                               :retry-if (constantly false)})]
      (is (= 1 @inv))))

  (testing "retries if failure"
    (let [inv (atom 0)
          r @(sut/async-retry #(md/success-deferred (swap! inv inc))
                              {:max-retries 10
                               :retry-if (fn [v] (= 1 v))
                               :backoff (sut/constant-delay 100)})]
      (is (= 2 @inv))
      (is (= 2 r) "should return last result")))

  (testing "returns last result if max retries reached"
    (is (= ::error @(sut/async-retry #(md/success-deferred ::error)
                                     {:max-retries 1
                                      :retry-if (constantly true)
                                      :backoff (sut/constant-delay 100)})))))

(deftest retry
  (testing "invokes target fn"
    (is (= ::result (sut/retry (constantly ::result)
                               {:max-retries 1
                                :retry-if (constantly false)}))))

  (testing "retries invocation if condition matches"
    (let [inv (atom 0)]
      (is (= 2 (sut/retry (fn []
                            (swap! inv inc))
                          {:max-retries 10
                           :retry-if #(= 1 %)
                           :backoff (sut/constant-delay 100)})))
      (is (= 2 @inv))))

  (testing "returns last result if max retries reached"
    (let [inv (atom 0)]
      (is (= 2 (sut/retry (fn []
                            (swap! inv inc))
                          {:max-retries 2
                           :retry-if (constantly true)
                           :backoff (sut/constant-delay 100)})))
      (is (= 2 @inv)))))

(deftest constant-delay
  (testing "always returns same timeout"
    (is (every? (partial = 1000) (repeatedly 10 (sut/constant-delay 1000))))))

(deftest incremental-delay
  (testing "adds to initial value"
    (let [d (sut/incremental-delay 100 10)]
      (is (= 100 (d)))
      (is (= 110 (d))))))

(deftest exponential-delay
  (testing "doubles value on each call"
    (let [e (sut/exponential-delay 100)]
      (is (= 100 (e)))
      (is (= 200 (e)))
      (is (= 400 (e))))))

(deftest with-max
  (testing "caps delay at max"
    (let [m (sut/with-max (sut/exponential-delay 100) 250)]
      (is (= 100 (m)))
      (is (= 200 (m)))
      (is (= 250 (m))))))
