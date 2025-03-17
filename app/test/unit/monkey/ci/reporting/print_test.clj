(ns monkey.ci.reporting.print-test
  (:require [clansi :as cl]
            [clojure.test :refer [deftest testing is]]
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
      (is (cs/includes? s url))))

  (testing "makes header the same size as values"))

(deftest print-build-event
  (->> [:script/start
        :pipeline/start
        :step/start
        :step/end
        :pipeline/end
        :script/end]
       (map (fn [t]
              (testing (format "build event `%s`" t)
                (testing "prints to stdout"
                  (let [s (capture-out {:type :build/event
                                        :event {:type t}})]
                    (is (not-empty s))
                    (is (not (cs/includes? s "Warning")))))

                (testing "prints build id"
                  (let [s (capture-out {:type :build/event
                                        :event {:type t
                                                :sid ["cust" "proj" "repo" "test-build"]}})]
                    (is (cs/includes? s "test-build")))))))
       (doall)))

(deftest build-list
  (testing "without builds"
    (testing "generates 'no builds' message"
      (let [l (sut/build-list [])]
        (is (= 1 (count l))))))
   
  (testing "with builds"
    (let [builds [{:id "build1"
                   :timestamp "ts1"
                   :result :success}
                  {:id "other"
                   :timestamp "ts1"
                   :result :success}]]
      
      (testing "aligns title according to item size"
        (is (cs/includes? (-> builds
                              (sut/build-list)
                              (first))
                          "Id        Timestamp    Result  ")))

      (testing "aligns items to size"
        (is (cs/starts-with? (-> builds
                                 (sut/build-list)
                                 (second))
                             "build1    ts1          success"))))))

(deftest print-build-list
  (testing "prints for empty list"
    (is (not-empty (capture-out {:type :build/list
                                 :builds []})))))

(deftest print-verify-success
  (testing "prints success message"
    (is (not-empty (capture-out {:type :verify/success
                                 :jobs [{:id "test-job"}]})))))

(deftest print-verify-failed
  (testing "prints error"
    (is (not-empty (capture-out {:type :verify/failed
                                 :message "test error"})))))

(deftest print-verify
  (testing "clj linter"
    (testing "prints error on error"
      (is (not-empty (capture-out {:type :verify/result
                                   :result [{:type :clj
                                             :result :errors
                                             :details {:summary {:error 1}}}]}))))
    
    (testing "prints warnings"
      (is (not-empty (capture-out {:type :verify/result
                                   :result [{:type :clj
                                             :result :warnings
                                             :details {:summary {:warning 1}}}]})))))

  (testing "yaml"
    (testing "prints errors"
      (is (not-empty (capture-out {:type :verify/result
                                   :result [{:type :yaml
                                             :result :errors
                                             :details {:errors ["test error"]}}]})))))

  (testing "prints success when no errors"
    (is (not-empty (capture-out {:type :verify/result
                                 :result [{:type :clj
                                           :result :success
                                           :details {:summary {:error 0}}}]})))))

(deftest print-test-starting
  (testing "prints message"
    (is (not-empty (capture-out {:type :test/starting})))))

(deftest unknown-types
  (testing "prints warning"
    (is (string? (capture-out {:type :unkown/type})))))
