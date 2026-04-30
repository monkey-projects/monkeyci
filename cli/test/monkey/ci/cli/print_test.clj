(ns monkey.ci.cli.print-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [monkey.ci.cli.print :as sut]))

(deftest print-version-test
  (testing "prints the version number"
    (let [out (with-out-str (sut/print-version "1.2.3"))]
      (is (str/includes? out "1.2.3"))))

  (testing "output contains ANSI escape codes"
    (let [out (with-out-str (sut/print-version "1.2.3"))]
      (is (str/includes? out "\u001b[")))))

(deftest print-summary-test
  (let [summary {:error 2 :warning 3 :info 1 :files 5 :duration 123}]
    (testing "prints files count"
      (let [out (with-out-str (sut/print-summary summary))]
        (is (str/includes? out "5"))))

    (testing "prints error count"
      (let [out (with-out-str (sut/print-summary summary))]
        (is (str/includes? out "2"))))

    (testing "prints warning count"
      (let [out (with-out-str (sut/print-summary summary))]
        (is (str/includes? out "3"))))

    (testing "prints info count"
      (let [out (with-out-str (sut/print-summary summary))]
        (is (str/includes? out "1"))))

    (testing "prints duration in milliseconds"
      (let [out (with-out-str (sut/print-summary summary))]
        (is (str/includes? out "123ms"))))

    (testing "output contains ANSI escape codes"
      (let [out (with-out-str (sut/print-summary summary))]
        (is (str/includes? out "\u001b["))))

    (testing "does not print ok message when there are errors or warnings"
      (let [out (with-out-str (sut/print-summary summary))]
        (is (not (str/includes? out "ok"))))))

  (testing "prints ok message in when no errors and no warnings"
    (let [out (with-out-str (sut/print-summary {:error 0 :warning 0 :info 0 :files 3 :duration 50}))]
      (is (str/includes? out "ok")))))

(deftest print-finding-test
  (testing "prints filename, row and message"
    (let [out (with-out-str
                (sut/print-finding {:filename "src/foo.clj"
                                    :row      42
                                    :message  "unused binding"}))]
      (is (str/includes? out "src/foo.clj"))
      (is (str/includes? out "42"))
      (is (str/includes? out "unused binding"))))

  (testing "output contains ANSI escape codes"
    (let [out (with-out-str
                (sut/print-finding {:filename "src/foo.clj"
                                    :row      1
                                    :message  "unused binding"}))]
      (is (str/includes? out "\u001b[")))))

(deftest print-findings-test
  (testing "prints a header with the findings count"
    (let [findings [{:filename "a.clj" :row 1 :message "msg1"}
                    {:filename "b.clj" :row 2 :message "msg2"}]
          out      (with-out-str (sut/print-findings findings))]
      (is (str/includes? out "2"))))

  (testing "prints each finding"
    (let [findings [{:filename "a.clj" :row 1 :message "msg1"}
                    {:filename "b.clj" :row 2 :message "msg2"}]
          out      (with-out-str (sut/print-findings findings))]
      (is (str/includes? out "a.clj"))
      (is (str/includes? out "b.clj"))
      (is (str/includes? out "msg1"))
      (is (str/includes? out "msg2")))))
