(ns monkey.ci.test.cli-test
  "Tests for the CLI configuration"
  (:require [clojure.test :refer :all]
            [monkey.ci.core :as core]))

(deftest cli
  (let [last-cmd (atom nil)
        run-cli (fn [& args]
                  (is (= :exit (core/run-cli args)))
                  @last-cmd)]
    (with-redefs [cli-matic.platform/exit-script (constantly :exit)
                  core/run-cmd (fn [cmd args]
                                 (reset! last-cmd {:cmd cmd
                                                   :args args})
                                 0)]

      (testing "`version` command prints version"
        (let [lc (run-cli "version")]
          (is (= core/print-version (:cmd lc)))))

      (testing "`build` command"
        (testing "accepts script dir `-d`"
          (let [lc (run-cli "build" "-d" "test-dir")]
            (is (= "test-dir" (get-in lc [:args :dir])))))

        (testing "accepts global working dir `-w`"
          (let [lc (run-cli "-w" "work-dir" "build")]
            (is (= "work-dir" (get-in lc [:args :workdir])))))))))
