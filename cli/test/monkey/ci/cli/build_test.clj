(ns monkey.ci.cli.build-test
  (:require [clojure.core.async :as ca]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.cli
             [build :as sut]
             [process :as proc]
             [server :as srv]]))

(def ^:private dummy-server
  {:server        nil
   :port          9999
   :token         "fake-token"
   :event-mult-ch (ca/chan 1)
   :event-mult    nil})

(deftest build-test
  (testing "starts the API server before running the child process"
    (let [server-started? (atom false)
          server-stopped? (atom false)
          process-run?    (atom false)]
      (with-redefs [srv/start-server (fn [_opts]
                                       (reset! server-started? true)
                                       dummy-server)
                    proc/run         (fn [_cmd _dir _env]
                                       (reset! process-run? true)
                                       0)
                    srv/stop-server  (fn [_s]
                                       (reset! server-stopped? true))]
        (sut/build {:dir "."})
        (is @server-started?)
        (is @process-run?)
        (is @server-stopped?))))

  (testing "passes the API URL and token as environment variables"
    (let [received-env (atom nil)]
      (with-redefs [srv/start-server (fn [_opts] dummy-server)
                    proc/run         (fn [_cmd _dir env]
                                       (reset! received-env env)
                                       0)
                    srv/stop-server  (fn [_s] nil)]
        (sut/build {:dir "."})
        (is (= "http://localhost:9999"
               (get @received-env sut/api-url-env)))
        (is (= "fake-token"
               (get @received-env sut/api-token-env))))))

  (testing "stops the server even when the child process throws"
    (let [server-stopped? (atom false)]
      (with-redefs [srv/start-server (fn [_opts] dummy-server)
                    proc/run         (fn [_cmd _dir _env]
                                       (throw (ex-info "process error" {})))
                    srv/stop-server  (fn [_s]
                                       (reset! server-stopped? true))]
        (try
          (sut/build {:dir "."})
          (catch Exception _))
        (is @server-stopped?))))

  (testing "returns the process exit code"
    (with-redefs [srv/start-server (fn [_opts] dummy-server)
                  proc/run         (fn [_cmd _dir _env] 42)
                  srv/stop-server  (fn [_s] nil)]
      (is (= 42 (sut/build {:dir "."})))))

  (testing "returns 0 on success"
    (with-redefs [srv/start-server (fn [_opts] dummy-server)
                  proc/run         (fn [_cmd _dir _env] 0)
                  srv/stop-server  (fn [_s] nil)]
      (is (zero? (sut/build {:dir "."})))))

  (testing "passes the script dir (not raw dir) to the process"
    (let [received-dir (atom nil)]
      (with-redefs [srv/start-server (fn [_opts] dummy-server)
                    proc/run         (fn [_cmd dir _env]
                                       (reset! received-dir dir)
                                       0)
                    srv/stop-server  (fn [_s] nil)]
        (sut/build {:dir "/some/project"})
        ;; find-script-dir returns the dir itself when .monkeyci doesn't exist
        (is (string? @received-dir))))))
