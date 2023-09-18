(ns monkey.ci.test.core-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as c]
            [monkey.ci.core :as sut]
            [monkey.ci.web.handler :as web]))

(deftest cli-config
  (testing "creates cli configuration on start"
    (is (some? (-> (sut/new-cli {})
                   (c/start)
                   :cli-config
                   :subcommands)))))

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

(deftest server
  (with-redefs [web/start-server (constantly :test-server)
                web/wait-until-stopped identity]
    
    (testing "starts http server and blocks"
      (is (= :test-server (sut/server {} {}))))

    (testing "registers shutdown hook"
      ;; TODO
      )))
