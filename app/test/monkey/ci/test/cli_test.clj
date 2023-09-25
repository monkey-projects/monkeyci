(ns monkey.ci.test.cli-test
  "Tests for the CLI configuration"
  (:require [clojure.test :refer :all]
            [cli-matic.core :as cli]
            [monkey.ci
             [commands :as cmd]
             [core :as core]]))

(deftest cli
  (let [last-cmd (atom nil)
        test-invoker (fn [cmd env]
                       (fn [args]
                         (reset! last-cmd {:cmd (:command cmd)
                                           :env env
                                           :args args})
                         0))
        test-config (core/make-cli-config {:env {:monkeyci-runner-type :noop}
                                           :cmd-invoker test-invoker})
        run-cli (fn [& args]
                  (is (= :exit (cli/run-cmd args test-config)))
                  @last-cmd)]
    (with-redefs [cli-matic.platform/exit-script (constantly :exit)]

      (testing "`build` command"
        (testing "runs `build` command"
          (let [lc (run-cli "build")]
            (is (= cmd/build (:cmd lc)))))
        
        (testing "accepts script dir `-d`"
          (let [lc (run-cli "build" "-d" "test-dir")]
            (is (= "test-dir" (get-in lc [:args :dir])))))

        (testing "uses default script dir when not provided"
          (let [lc (run-cli "build")]
            (is (= ".monkeyci/" (get-in lc [:args :dir])))))

        (testing "accepts global working dir `-w`"
          (let [lc (run-cli "-w" "work-dir" "build")]
            (is (= "work-dir" (get-in lc [:args :workdir])))))

        (testing "allows specifying pipeline name"
          (let [lc (run-cli "build" "-p" "test-pipeline")]
            (is (= "test-pipeline" (get-in lc [:args :pipeline])))))
                
        (testing "accepts dev mode"
          (let [lc (run-cli "--dev-mode" "build" "test-dir")]
            (is (true? (get-in lc [:args :dev-mode]))))))

      (testing "`server` command"
        (testing "runs `server` command"
          (is (= cmd/http-server (:cmd (run-cli "server")))))

        (testing "accepts listening port `-p`"
          (is (= 1234 (-> (run-cli "server" "-p" "1234")
                          :args
                          :port))))))))
