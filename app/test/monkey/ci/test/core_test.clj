(ns monkey.ci.test.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [core :as sut]
             [events :as e]
             [utils :as u]]
            [monkey.ci.web.handler :as web]
            [monkey.ci.test.helpers :as h]))

(deftest main-test
  (with-redefs [clojure.core/shutdown-agents (constantly nil)
                cli-matic.platform/exit-script (constantly :exit)]
    (testing "runs cli"
      (is (= :exit (sut/-main "-h"))))))

(deftest default-invoker
  (let [hooks (atom [])]
    (with-redefs [u/add-shutdown-hook! (partial swap! hooks conj)]
      
      (testing "does nothing when no bus"
        (is (nil? ((sut/default-invoker {:command :test} {}) {}))))

      (testing "posts `command/invoked` event and waits for `command/completed`"
        (let [cmd {:command :test
                   :requires [:bus]}  ; Test command, only requires a bus
              bus (e/make-bus)
              ;; Setup a system with a custom bus
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
          (is (not= :timeout (h/try-take out 500 :timeout)))))

      (testing "registers shutdown hook"
        (let [cmd {:command :test
                   :requires [:bus]}  ; Test command, only requires a bus
              bus (e/make-bus)
              ;; Setup a system with a custom bus
              inv (sut/default-invoker cmd {} (c/system-map :bus bus))
              out (inv {})]
          (is (pos? (count @hooks))))))))

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

