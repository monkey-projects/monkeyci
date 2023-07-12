(ns monkey.ci.test.core-test
  (:require [clojure.test :refer :all]
            [monkey.ci.core :as sut]))

(deftest main-test
  (with-redefs [clojure.core/shutdown-agents (constantly nil)
                cli-matic.platform/exit-script (constantly nil)]
    (testing "main returns nil"
      (is (nil? (sut/-main "version"))))))

(deftest build
  (testing "runs build script"
    (is (some? (sut/build {:runner {:type :noop}}
                          {:dir "examples/basic-clj"})))))
