(ns monkey.ci.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [config :as config]
             [core :as sut]
             [events :as e]
             [spec :as spec]
             [utils :as u]]
            [monkey.ci.web.handler :as web]
            [monkey.ci.helpers :as h]))

(deftest main-test
  (with-redefs [clojure.core/shutdown-agents (constantly nil)
                cli-matic.platform/exit-script (constantly :exit)]
    (testing "runs cli"
      (is (= :exit (sut/-main "-?"))))))

(deftest system-invoker
  (let [hooks (atom [])]
    (with-redefs [u/add-shutdown-hook! (partial swap! hooks conj)]

      (testing "creates a fn"
        (is (fn? (sut/system-invoker {} {}))))

      (testing "invokes command with context"
        (let [inv (sut/system-invoker {:command (constantly "test-result")} {})]
          (is (= "test-result" (inv {})))))
      
      (testing "activates required components"
        (let [cmd {:command #(get-in % [:system :test-component])
                   :requires [:test-component]}
              sys (c/system-map :test-component :test-component-value)
              inv (sut/system-invoker cmd {} sys)
              r (inv {})]
          (is (= :test-component-value r))))

      (testing "registers shutdown hook"
        (h/with-bus
          (fn [bus]
            (let [cmd {:command :test
                       :requires [:bus]} ; Test command, only requires a bus
                  ;; Setup a system with a custom bus
                  inv (sut/system-invoker cmd {} (c/system-map :bus bus))
                  out (inv {})]
              (is (pos? (count @hooks))))))))))

