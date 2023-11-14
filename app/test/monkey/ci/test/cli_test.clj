(ns monkey.ci.test.cli-test
  "Tests for the CLI configuration"
  (:require [clojure.test :refer :all]
            [cli-matic.core :as cli]
            [monkey.ci
             [cli :as sut]
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
        (testing "`run` subcommand"
          (testing "runs `run-build` command"
            (let [lc (run-cli "build" "run")]
              (is (= cmd/run-build (:cmd lc)))))

          (testing "accepts script dir `-d`"
            (let [lc (run-cli "build" "run" "-d" "test-dir")]
              (is (= "test-dir" (get-in lc [:args :dir])))))

          (testing "uses default script dir when not provided"
            (let [lc (run-cli "build" "run")]
              (is (= ".monkeyci" (get-in lc [:args :dir])))))

          (testing "accepts global working dir `-w`"
            (let [lc (run-cli "-w" "work-dir" "build" "run")]
              (is (= "work-dir" (get-in lc [:args :workdir])))))

          (testing "allows specifying pipeline name"
            (let [lc (run-cli "build" "run" "-p" "test-pipeline")]
              (is (= "test-pipeline" (get-in lc [:args :pipeline])))))
          
          (testing "accepts dev mode"
            (let [lc (run-cli "--dev-mode" "build" "run")]
              (is (true? (get-in lc [:args :dev-mode])))))

          (testing "accepts git url"
            (let [lc (run-cli "build" "run" "--git-url" "http://test-url")]
              (is (= "http://test-url" (get-in lc [:args :git-url])))))

          (testing "accepts git branch"
            (let [lc (run-cli "build" "run" "--branch" "test-branch")]
              (is (= "test-branch" (get-in lc [:args :branch])))))

          (testing "accepts commit id"
            (let [lc (run-cli "build" "run" "--commit-id" "test-id")]
              (is (= "test-id" (get-in lc [:args :commit-id])))))

          (testing "accepts config file"
            (is (= "test-file" (-> (run-cli "-c" "test-file" "build" "run")
                                   (get-in [:args :config-file]))))
            (is (= "test-file" (-> (run-cli "--config-file" "test-file" "build" "run")
                                   (get-in [:args :config-file])))))

          (testing "accepts build sid"
            (is (= "test-sid" (-> (run-cli "build" "run" "--sid" "test-sid")
                                  (get-in [:args :sid]))))))
        
        (testing "`watch` command"
          (testing "runs `watch` command"
            (let [lc (run-cli "build" "-c" "test-customer" "watch")]
              (is (= cmd/watch (:cmd lc)))))

          (testing "accepts server url"
            (is (= "http://test" (-> (run-cli "build" "-s" "http://test" "watch")
                                     (get-in [:args :server]))))))
        
        (testing "`list` subcommand"
          (testing "runs `list-builds` command"
            (let [lc (run-cli "build" "list")]
              (is (= cmd/list-builds (:cmd lc)))))))

      (testing "`server` command"
        (testing "runs `server` command"
          (is (= cmd/http-server (:cmd (run-cli "server")))))

        (testing "accepts listening port `-p`"
          (is (= 1234 (-> (run-cli "server" "-p" "1234")
                          :args
                          :port))))))))

(deftest set-invoker
  (testing "applies invoker to `runs` in commands"
    (is (= :applied (-> {:subcommands [{:runs :not-applied}]}
                        (sut/set-invoker (constantly :applied))
                        :subcommands
                        first
                        :runs)))))
