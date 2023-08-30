(ns monkey.ci.test.core-test
  (:require [clojure.test :refer :all]
            [monkey.ci.core :as sut]))

(deftest main-test
  (with-redefs [clojure.core/shutdown-agents (constantly nil)
                cli-matic.platform/exit-script (constantly :exit)]
    (testing "main returns nil"
      (is (= :exit (sut/-main "version"))))

    (testing "accepts working directory"
      (is (= :exit (sut/-main "-w" "test" "version"))))))

(deftest build
  (testing "invokes runner"
    (is (some? (sut/build {:monkeyci-runner-type :noop}
                          {:dir "examples/basic-clj"}))))

  (testing "returns exit code"
    (is (number? (sut/build {:monkeyci-runner-type :noop}
                            {:dir "examples/basic-clj"})))))
