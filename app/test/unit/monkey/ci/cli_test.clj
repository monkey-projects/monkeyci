(ns monkey.ci.cli-test
  "Tests for the CLI configuration"
  (:require [cli-matic.core :as cli]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [monkey.ci
             [cli :as sut]
             [commands :as cmd]
             [core :as core]]
            [monkey.ci.test.helpers :as h]))

(deftest cli
  (let [last-cmd (atom nil)
        test-invoker (fn [cmd env]
                       (fn [args]
                         (reset! last-cmd {:cmd (:command cmd)
                                           :env env
                                           :args args})
                         0))]
    (with-redefs [cli-matic.platform/exit-script (constantly :exit)]

      (testing "user config"
        (let [test-config (core/make-cli-config sut/user-config
                                                {:env {:monkeyci-runner-type :noop}
                                                 :cmd-invoker test-invoker})
              run-cli (fn [& args]
                        (is (= :exit (cli/run-cmd args test-config)))
                        @last-cmd)]
          (testing "`build` command"
            (testing "`run` subcommand"
              (testing "runs `run-build` command"
                (let [lc (run-cli "build" "run")]
                  (is (= cmd/run-build-local (:cmd lc)))))

              (testing "accepts script dir `-d`"
                (let [lc (run-cli "build" "run" "-d" "test-dir")]
                  (is (= "test-dir" (get-in lc [:args :dir])))))

              (testing "uses default script dir when not provided"
                (let [lc (run-cli "build" "run")]
                  (is (= ".monkeyci" (get-in lc [:args :dir])))))

              (testing "accepts global working dir `-w`"
                (let [lc (run-cli "-w" "work-dir" "build" "run")]
                  (is (= "work-dir" (get-in lc [:args :workdir])))))
              
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
                (let [lc (run-cli "-c" "first.edn" "-c" "second.edn" "build" "run")]
                  (is (= ["first.edn" "second.edn"]
                         (get-in lc [:args :config-file])))))
              
              (testing "accepts build sid"
                (is (= "test-sid" (-> (run-cli "build" "run" "--sid" "test-sid")
                                      (get-in [:args :sid])))))

              (testing "accepts multiple build params"
                (is (= ["key1=value1" "key2=value2"]
                       (-> (run-cli "build" "run" "-p" "key1=value1" "--param" "key2=value2")
                           (get-in [:args :param])))))

              (testing "accepts build param files"
                (is (= ["params.yml" "params.edn"]
                       (-> (run-cli "build" "run"
                                    "--param-file" "params.yml"
                                    "--param-file" "params.edn")
                           (get-in [:args :param-file])))))

              (testing "accepts api url"
                (is (= "http://test"
                       (-> (run-cli "build"
                                    "--api" "http://test"
                                    "run")
                           (get-in [:args :api])))))

              (testing "accepts api key"
                (is (= "test-key"
                       (-> (run-cli "build"
                                    "--api-key" "test-key"
                                    "run")
                           (get-in [:args :api-key])))))

              (testing "accepts job filter"
                (is (= ["test-job"]
                       (-> (run-cli "build"
                                    "run"
                                    "--filter" "test-job")
                           (get-in [:args :filter]))))))
            
            #_(testing "`watch` subcommand"
                (testing "runs `watch` command"
                  (let [lc (run-cli "build" "-c" "test-customer" "watch")]
                    (is (= cmd/watch (:cmd lc)))))

                (testing "accepts server url"
                  (is (= "http://test" (-> (run-cli "build" "-s" "http://test" "watch")
                                           (get-in [:args :server]))))))
            
            #_(testing "`list` subcommand"
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
                  (is (= cmd/run-tests (:cmd lc)))))))))

      (testing "internal config"
        (let [test-config (core/make-cli-config sut/internal-config
                                                {:env {:monkeyci-runner-type :noop}
                                                 :cmd-invoker test-invoker})
              run-cli (fn [& args]
                        (is (= :exit (cli/run-cmd args test-config)))
                        @last-cmd)]
          (testing "`internal` commands"
            (testing "`server` command"
              (testing "runs `server` command"
                (is (= cmd/http-server (:cmd (run-cli "internal" "server")))))

              (testing "accepts listening port `-p`"
                (is (= 1234 (-> (run-cli "internal" "server" "-p" "1234")
                                :args
                                :port)))))

            (testing "`sidecar` command"
              (testing "runs `sidecar` command"
                (is (= cmd/sidecar (:cmd (run-cli "internal" "sidecar" "-e" "events" "-s" "start")))))

              (testing "accepts `events-file` or `-e` option"
                (is (= "test-file" (-> (run-cli "internal" "sidecar" "-e" "test-file" "-s" "start-file")
                                       :args
                                       :events-file)))
                (is (= "test-file" (-> (run-cli "internal" "sidecar" "--events-file" "test-file" "-s" "start-file")
                                       :args
                                       :events-file))))

              (testing "accepts `start-file` or `-s` option"
                (is (= "test-file" (-> (run-cli "internal" "sidecar" "-s" "test-file" "-e" "events-file")
                                       :args
                                       :start-file)))
                (is (= "test-file" (-> (run-cli "internal" "sidecar" "--start-file" "test-file" "-e" "events-file")
                                       :args
                                       :start-file))))

              (testing "accepts `abort-file` or `-a` option"
                (is (= "abort-file" (-> (run-cli "internal" "sidecar" "-a" "abort-file")
                                        :args
                                        :abort-file)))
                (is (= "abort-file" (-> (run-cli "internal" "sidecar" "--abort-file" "abort-file")
                                        :args
                                        :abort-file))))

              (h/with-tmp-dir dir
                (let [script-config (io/file dir "script.edn")
                      p (.getCanonicalPath script-config)]
                  (is (nil? (spit script-config (pr-str {:key "value"}))))
                  
                  (testing "accepts `job-config` or `-t` option"
                    (is (= {:key "value"}
                           (-> (run-cli "internal" "sidecar" "-e" "events" "-s" "start" "--job-config" p)
                               :args
                               :job-config)))
                    (is (= {:key "value"}
                           (-> (run-cli "internal" "sidecar" "-e" "events" "-s" "start" "-t" p)
                               :args
                               :job-config)))))))

            (testing "`controller` command"
              (testing "runs `controller` command"
                (is (= cmd/controller (:cmd (run-cli "internal" "controller")))))))

          (testing "`admin` command"
            (testing "`issue` runs issue-creds command"
              (is (= cmd/issue-creds (:cmd (run-cli "admin" "-u" "testuser" "-k" "test-key" "issue")))))

            (testing "`reaper` runs cancel-dangling-builds command"
              (is (= cmd/cancel-dangling-builds
                     (:cmd (run-cli "admin" "-u" "testuser" "-k" "test-key" "reaper")))))))))))

(deftest set-invoker
  (testing "applies invoker to `runs` in commands"
    (is (= :applied (-> {:subcommands [{:runs :not-applied}]}
                        (sut/set-invoker (constantly :applied))
                        :subcommands
                        first
                        :runs)))))
