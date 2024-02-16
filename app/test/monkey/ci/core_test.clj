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
      
      (testing "passes runtime when app-mode is specified"
        (let [inv (sut/system-invoker {:command identity
                                       :app-mode :cli}
                                      {})]
          ;; Runtime has a config entry
          (is (some? (:config (inv {})))))))))

