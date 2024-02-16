(ns monkey.ci.core-test
  (:require [clojure.test :refer :all]
            [monkey.ci
             [core :as sut]
             [utils :as u]]))

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

