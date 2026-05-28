(ns monkey.ci.script.interceptors-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.script.interceptors :as sut]))

(deftest execute
  (testing "invokes all interceptors `enter` stages in order"
    (is (= [::first
            ::second]
           (-> (sut/execute [{:enter #(update % :res conj ::first)}
                             {:enter #(update % :res conj ::second)}]
                            {:res []})
               :res))))

  (testing "invokes all interceptors `leave` stages in reverse order"
    (is (= [::second
            ::first]
           (-> (sut/execute [{:leave #(update % :res conj ::first)}
                             {:leave #(update % :res conj ::second)}]
                            {:res []})
               :res))))

  (testing "applies `enter` in order, then `leave` in revers order"
    (is (= [::first-enter
            ::second-enter
            ::second-leave
            ::first-leave]
           (-> (sut/execute [{:enter #(update % :res conj ::first-enter)
                              :leave #(update % :res conj ::first-leave)}
                             {:enter #(update % :res conj ::second-enter)
                              :leave #(update % :res conj ::second-leave)}]
                            {:res []})
               :res))))

  (testing "on exception"
    (let [ex (ex-info "test error" {})
          error-int
          {:error (fn [ctx ex]
                    (assoc ctx ::error-handled ex))}
          failing-int
          {:enter (fn [_]
                    (throw ex))}
          other-int
          {:enter #(update % ::other conj :enter)
           :leave #(update % ::other conj :leave)}]

      (let [r (-> [error-int
                   failing-int
                   other-int]
                  (sut/execute {}))]
        (testing "invokes error handler"
          (is (= ex (::error-handled r))))

        (testing "skips following `enter` stages"
          (is (not (contains? (set (::other r)) :enter))))

        (testing "skips `leave` stages before error handler"
          (is (not (contains? (set (::other r)) :leave)))))

      (testing "invokes `leave` stages after error handler"
        (is (contains? (-> [other-int
                            error-int
                            failing-int]
                           (sut/execute {})
                           ::other
                           set)
                       :leave)))

      (testing "throws unhandled exception"
        (is (thrown? Exception
                     (-> [failing-int
                          other-int]
                         (sut/execute {}))))))))
