(ns monkey.ci.test.reporting.print-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci.reporting.print :as sut]))

(defn- capture-out [msg]
  (let [w (java.io.StringWriter.)]
    (binding [*out* w]
      (sut/print-reporter msg))
    (.toString w)))

(deftest print-server-start
  (testing "prints to stdout"
    (let [s (capture-out {:type :server/started
                          :http {:port 1234}})]
      (is (cs/includes? s "Server started"))
      (is (cs/includes? s "1234")))))

(deftest print-watch-start
  (testing "prints to stdout"
    (let [url "http://test"
          s (capture-out {:type :watch/started
                          :url url})]
      (is (cs/includes? s url)))))

(deftest unknown-types
  (testing "prints warning"
    (is (string? (capture-out {:type :unkown/type})))))
