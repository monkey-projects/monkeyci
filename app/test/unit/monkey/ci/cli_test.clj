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
