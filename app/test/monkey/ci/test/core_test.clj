(ns monkey.ci.test.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
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

      (testing "creates a fn"
        (is (fn? (sut/default-invoker {} {}))))
      
      (testing "does nothing when no bus"
        (is (nil? ((sut/default-invoker {:command :no-bus-test} {} {}) {}))))

      (testing "posts `command/invoked` event and waits for `command/completed`"
        (h/with-bus
          (fn [bus]
            (let [cmd {:command :test-1
                       :requires [:bus]} ; Test command, only requires a bus
                  ;; Setup a system with a custom bus
                  inv (sut/default-invoker cmd {} (c/system-map :bus bus))
                  _ (e/register-handler bus
                                        :command/invoked
                                        (fn [{:keys [command bus] :as evt}]
                                          (when (= :test-1 command)
                                            ;; Complete immediately
                                            (log/debug "Posting complete event")
                                            (e/post-event bus {:type :command/completed
                                                               :command command}))))
                  out (inv {})]
              (is (= 0 (h/try-take out 500 :timeout)))))))

      (testing "returns exit code"
        (h/with-bus
          (fn [bus]
            (let [cmd {:command :test-2
                       :requires [:bus]} ; Test command, only requires a bus
                  ;; Setup a system with a custom bus
                  inv (sut/default-invoker cmd {} (c/system-map :bus bus))
                  _ (e/register-handler bus
                                        :command/invoked
                                        (fn [{:keys [command bus]}]
                                          (when (= :test-2 command)
                                            ;; Complete immediately
                                            (e/post-event bus {:type :command/completed
                                                               :command command
                                                               :exit 5}))))
                  out (inv {})]
              (is (= 5 (h/try-take out 500 :timeout)))))))

      (testing "passes dependent components in event"
        (h/with-bus
          (fn [bus]
            (let [cmd {:command :test
                       :requires [:bus :config]}
                  ;; Setup a system with a custom bus
                  inv (sut/default-invoker cmd {} (c/system-map :bus bus))
                  _ (e/register-handler bus
                                        :command/invoked
                                        (fn [{:keys [command config bus]}]
                                          (when (= :test command)
                                            ;; Complete immediately
                                            (e/post-event bus {:type :command/completed
                                                               :command command
                                                               :exit (if (some? config) 0 1)}))))
                  out (inv {})
                  recv (h/try-take out 500 :timeout)]
              (is (zero? recv))))))

      (testing "registers shutdown hook"
        (h/with-bus
          (fn [bus]
            (let [cmd {:command :test
                       :requires [:bus]} ; Test command, only requires a bus
                  ;; Setup a system with a custom bus
                  inv (sut/default-invoker cmd {} (c/system-map :bus bus))
                  out (inv {})]
              (is (pos? (count @hooks))))))))))

