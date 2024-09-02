(ns monkey.ci.runtime.script-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.artifacts :as art]
            [monkey.ci.runtime.script :as sut]))

(deftest with-runtime
  (testing "invokes target function with runtime"
    (is (= ::invoked (sut/with-runtime {:key :value} (constantly ::invoked)))))

  (testing "drops workspace from runtime"
    (is (nil? (sut/with-runtime {} :workspace))))

  (testing "adds artifact repo"
    (is (art/repo? (sut/with-runtime
                     {:artifacts {:type :disk :dir "/tmp"}}
                     :artifacts))))

  (testing "adds cache repo"
    (is (art/repo? (sut/with-runtime
                     {:cache {:type :disk :dir "/tmp"}}
                     :cache)))))
