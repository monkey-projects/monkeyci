(ns monkey.ci.test.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.config :as sut]))

(deftest app-config
  (testing "provides default values"
    (is (= 3000 (-> (sut/app-config {} {})
                    :http
                    :port))))

  (testing "takes port from args"
    (is (= 1234 (-> (sut/app-config {} {:port 1234})
                    :http
                    :port))))

  (testing "takes github config from env"
    (is (= "test-secret" (-> {:monkeyci-github-secret "test-secret"}
                             (sut/app-config {})
                             :github
                             :secret))))

  (testing "sets runner type"
    (is (= :test-type (-> {:monkeyci-runner-type "test-type"}
                          (sut/app-config {})
                          :runner
                          :type))))

  (testing "sets `dev-mode` from args"
    (is (true? (->> {:dev-mode true}
                    (sut/app-config {})
                    :dev-mode)))))
