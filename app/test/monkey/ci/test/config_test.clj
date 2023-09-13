(ns monkey.ci.test.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.config :as sut]))

(deftest build-config
  (testing "provides default values"
    (is (= 3000 (-> (sut/build-config {} {})
                    :http
                    :port))))

  (testing "takes port from args"
    (is (= 1234 (-> (sut/build-config {} {:port 1234})
                    :http
                    :port))))

  (testing "takes github config from env"
    (is (= "test-secret" (-> {:monkeyci-github-secret "test-secret"}
                             (sut/build-config {})
                             :github
                             :secret)))))
