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
      (is (cs/includes? s "Server started at port 1234")))))
