(ns monkey.ci.test.cli-test
  "Tests for the CLI configuration"
  (:require [clojure.test :refer :all]
            [monkey.ci.core :as core]))

(deftest cli
  (let [last-cmd (atom nil)
        test-invoker (fn [cmd env]
                       (fn [args]
                         (reset! last-cmd {:cmd cmd
                                           :env env
                                           :args args})
                         0))
        test-config (core/make-cli-config {:env {:monkeyci-runner-type :noop}
                                           :cmd-invoker test-invoker})
        run-cli (fn [& args]
                  (is (= :exit (core/run-cli test-config args)))
                  @last-cmd)]
    (with-redefs [cli-matic.platform/exit-script (constantly :exit)]

      (testing "`version` command prints version"
        (let [lc (run-cli "version")]
          (is (= core/print-version (:cmd lc)))))

      (testing "`build` command"
        (testing "accepts script dir `-d`"
          (let [lc (run-cli "build" "-d" "test-dir")]
            (is (= "test-dir" (get-in lc [:args :dir])))))

        (testing "uses default script dir when not provided"
          (let [lc (run-cli "build")]
            (is (= ".monkeyci/" (get-in lc [:args :dir])))))

        (testing "accepts global working dir `-w`"
          (let [lc (run-cli "-w" "work-dir" "build")]
            (is (= "work-dir" (get-in lc [:args :workdir])))))))))
