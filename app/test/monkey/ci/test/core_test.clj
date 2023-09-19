(ns monkey.ci.test.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [core :as sut]
             [events :as e]]
            [monkey.ci.web.handler :as web]
            [monkey.ci.test.helpers :as h]))

(deftest main-test
  (with-redefs [clojure.core/shutdown-agents (constantly nil)
                cli-matic.platform/exit-script (constantly :exit)]
    (testing "runs cli"
      (is (= :exit (sut/-main "-h"))))))

(deftest default-invoker
  (testing "does nothing when no bus"
    (is (nil? ((sut/default-invoker {:command :test} {}) {}))))

  (testing "posts `command/invoked` event and waits for `command/completed`"
    (let [cmd {:command :test
               :requires [:bus]}
          bus (e/make-bus)
          inv (sut/default-invoker cmd {} (c/system-map :bus bus))
          _ (e/register-handler bus
                                :command/invoked
                                (fn [{:keys [command]}]
                                  (when (= :test command)
                                    ;; Complete immediately
                                    (e/post-event bus {:type :command/completed
                                                       :command command}))))
          out (inv {})]
      (is (some? out))
      (is (not= :timeout (h/try-take out 500 :timeout))))))

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
