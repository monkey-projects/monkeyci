(ns monkey.ci.test.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [commands :as sut]
             [spec :as spec]]))

(deftest build
  (testing "invokes runner from context"
    (let [ctx {:runner {:fn (constantly :invoked)}}]
      (is (= :invoked (sut/build ctx))))))

(deftest http-server
  (testing "returns a channel"
    (is (spec/channel? (sut/http-server {})))))
