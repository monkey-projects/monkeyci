(ns monkey.ci.cli-test
  "Tests for the CLI configuration"
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cli-matic.core :as cli]
            [monkey.ci
             [cli :as sut]
             [commands :as cmd]
             [core :as core]
             [helpers :as h]]))

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

          (testing "accepts git tag"
            (let [lc (run-cli "build" "run" "--tag" "test-tag")]
              (is (= "test-tag" (get-in lc [:args :tag])))))

          (testing "accepts commit id"
            (let [lc (run-cli "build" "run" "--commit-id" "test-id")]
              (is (= "test-id" (get-in lc [:args :commit-id])))))

          (testing "accepts config file"
            (is (= ["test-file"] (-> (run-cli "-c" "test-file" "build" "run")
                                     (get-in [:args :config-file]))))
            (is (= ["test-file"] (-> (run-cli "--config-file" "test-file" "build" "run")
                                     (get-in [:args :config-file])))))
          
          (testing "accepts multiple config files"
            (let [lc (run-cli "-c" "first.edn" "-c" "second.edn" "build" "list")]
              (is (= ["first.edn" "second.edn"]
                     (get-in lc [:args :config-file])))))
          
          (testing "accepts build sid"
            (is (= "test-sid" (-> (run-cli "build" "run" "--sid" "test-sid")
                                  (get-in [:args :sid]))))))
        
        (testing "`watch` subcommand"
          (testing "runs `watch` command"
            (let [lc (run-cli "build" "-c" "test-customer" "watch")]
              (is (= cmd/watch (:cmd lc)))))

          (testing "accepts server url"
            (is (= "http://test" (-> (run-cli "build" "-s" "http://test" "watch")
                                     (get-in [:args :server]))))))
        
        (testing "`list` subcommand"
          (testing "runs `list-builds` command"
            (let [lc (run-cli "build" "list")]
              (is (= cmd/list-builds (:cmd lc))))))

        (testing "`verify` subcommand"
          (testing "runs `verify-build` subcommand"
            (let [lc (run-cli "build" "verify")]
              (is (= cmd/verify-build (:cmd lc))))))

        (testing "`test` subcommand"
          (testing "runs `run-tests` subcommand"
            (let [lc (run-cli "build" "test")]
              (is (= cmd/run-tests (:cmd lc)))))))

      (testing "`server` command"
        (testing "runs `server` command"
          (is (= cmd/http-server (:cmd (run-cli "server")))))

        (testing "accepts listening port `-p`"
          (is (= 1234 (-> (run-cli "server" "-p" "1234")
                          :args
                          :port)))))

      (testing "`sidecar` command"
        (testing "runs `sidecar` command"
          (is (= cmd/sidecar (:cmd (run-cli "sidecar" "-e" "events" "-s" "start")))))

        (testing "accepts `events-file` or `-e` option"
          (is (= "test-file" (-> (run-cli "sidecar" "-e" "test-file" "-s" "start-file")
                                 :args
                                 :events-file)))
          (is (= "test-file" (-> (run-cli "sidecar" "--events-file" "test-file" "-s" "start-file")
                                 :args
                                 :events-file))))

        (testing "accepts `start-file` or `-s` option"
          (is (= "test-file" (-> (run-cli "sidecar" "-s" "test-file" "-e" "events-file")
                                 :args
                                 :start-file)))
          (is (= "test-file" (-> (run-cli "sidecar" "--start-file" "test-file" "-e" "events-file")
                                 :args
                                 :start-file))))

        (testing "accepts `abort-file` or `-a` option"
          (is (= "abort-file" (-> (run-cli "sidecar" "-a" "abort-file")
                                  :args
                                  :abort-file)))
          (is (= "abort-file" (-> (run-cli "sidecar" "--abort-file" "abort-file")
                                  :args
                                  :abort-file))))

        (h/with-tmp-dir dir
          (let [script-config (io/file dir "script.edn")
                p (.getCanonicalPath script-config)]
            (is (nil? (spit script-config (pr-str {:key "value"}))))
            
            (testing "accepts `job-config` or `-t` option"
              (is (= {:key "value"}
                     (-> (run-cli "sidecar" "-e" "events" "-s" "start" "--job-config" p)
                         :args
                         :job-config)))
              (is (= {:key "value"}
                     (-> (run-cli "sidecar" "-e" "events" "-s" "start" "-t" p)
                         :args
                         :job-config))))))))))

(deftest set-invoker
  (testing "applies invoker to `runs` in commands"
    (is (= :applied (-> {:subcommands [{:runs :not-applied}]}
                        (sut/set-invoker (constantly :applied))
                        :subcommands
                        first
                        :runs)))))
