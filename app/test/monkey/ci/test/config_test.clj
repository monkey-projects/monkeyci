(ns monkey.ci.test.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci
             [config :as sut]
             [spec :as spec]]))

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

  (testing "matches app config spec"
    (is (true? (s/valid? ::spec/app-config (sut/app-config {} {}))))))

(deftest script-config
  (testing "sets containers type"
    (is (= :test-type (-> {:monkeyci-containers-type "test-type"}
                          (sut/script-config {})
                          :containers
                          :type))))

  (testing "matches spec"
    (is (true? (s/valid? ::spec/script-config (sut/script-config {} {}))))))
