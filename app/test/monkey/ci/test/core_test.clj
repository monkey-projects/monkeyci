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
                   :subcommands))))

  (testing "removes cli config on stop"
    (is (nil? (-> (sut/map->CliConfig {:cli-config :test-config})
                  (c/stop)
                  :cli-config)))))

(deftest main-test
  (with-redefs [clojure.core/shutdown-agents (constantly nil)
                cli-matic.platform/exit-script (constantly :exit)]
    (testing "main returns nil"
      (is (= :exit (sut/-main "version"))))

    (testing "accepts working directory"
      (is (= :exit (sut/-main "-w" "test" "version"))))))

(deftest build
  (testing "invokes runner"
    (is (some? (sut/build {:args
                           {:dir "examples/basic-clj"}
                           :runner
                           {:type :noop}}))))

  (testing "returns exit code"
    (is (number? (sut/build {:args 
                             {:dir "examples/basic-clj"}
                             :runner
                             {:type :noop}})))))

(deftest server
  (with-redefs [web/start-server (constantly :test-server)
                web/wait-until-stopped identity]
    
    (testing "starts http server and blocks"
      (is (= :test-server (sut/server {}))))

    (testing "registers shutdown hook"
      ;; TODO
      )))
