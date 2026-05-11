(ns monkey.ci.cli.run-test
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.cli
             [config :as c]
             [events]
             [process :as proc]
             [run :as sut]
             [server :as srv]]
            [monkey.mailman
             [core :as mmc]
             [core-async :as mmca]]))

(def ^:private dummy-server
  (let [ch (ca/chan 1)]
    {:server        nil
     :port          9999
     :token         "fake-token"
     :event-mult-ch ch
     :event-mult    (ca/mult ch)}))

(defn- test-config [conf & [res]]
  (let [result (promise)]
    (deliver result (or res ::test-result))
    (c/set-ending conf result)))

(deftest build
  (let [broker (mmca/core-async-broker)
        p (promise)
        l (mmc/add-listener broker (fn [evt]
                                     (deliver p evt)
                                     nil))]
    (with-redefs [sut/setup-events (constantly broker)]
      (testing "starts the API server"
        (let [server-started? (atom false)
              server-stopped? (atom false)]
          (with-redefs [srv/start-server (fn [_opts]
                                           (reset! server-started? true)
                                           dummy-server)
                        srv/stop-server  (fn [_s]
                                           (reset! server-stopped? true))]
            (sut/build (test-config {:dir "."}))
            (is @server-started?)
            (is @server-stopped?))))

      (testing "posts `:build/pending` event to start"
        (is (= :build/pending
               (-> (deref p 100 ::timeout)
                   :type))))

      (let [received-conf (atom nil)]
        (with-redefs [sut/setup-events (fn [conf]
                                         (reset! received-conf conf)
                                         broker)
                      srv/start-server (fn [_opts] dummy-server)
                      srv/stop-server  (fn [_s] nil)]
          (sut/build (test-config {:dir "/some/project"}))
          
          (testing "passes the API URL and token to route config"
            (is (= "http://localhost:9999"
                   (-> @received-conf
                       (c/get-api)
                       :url)))
            (is (= "fake-token"
                   (-> @received-conf
                       (c/get-api)
                       :token))))

          (testing "passes the script dir (not raw dir) to the process"
            ;; find-script-dir returns the dir itself when .monkeyci doesn't exist
            (is (string? (-> @received-conf
                             (c/get-build)
                             :checkout-dir))))))

      (testing "stops the server even on error"
        (let [server-stopped? (atom false)]
          (with-redefs [srv/start-server (fn [_opts] dummy-server)
                        srv/stop-server  (fn [_s]
                                           (reset! server-stopped? true))
                        sut/setup-events (constantly nil)]
            (try
              (sut/build (test-config {:dir "."}))
              (catch Exception _))
            (is @server-stopped?))))

      (testing "returns the process exit code"
        (with-redefs [srv/start-server (fn [_opts] dummy-server)
                      srv/stop-server  (fn [_s] nil)]
          (is (= 42 (sut/build (test-config {:dir "."} 42)))))))))

(deftest setup-events
  (testing "creates mailman broker"
    (let [b (sut/setup-events {})]
      (is (satisfies? mmc/EventReceiver b)))))

(deftest build-routes-podman
  (testing "build passes work-dir in conf to setup-events"
    (let [received-conf (atom nil)]
      (with-redefs [sut/setup-events  (fn [conf]
                                        (reset! received-conf conf)
                                        (mmca/core-async-broker))
                    srv/start-server  (fn [_] dummy-server)
                    srv/stop-server   (fn [_] nil)
                    srv/server->url   (fn [_] "http://localhost:9999")]
        (let [result (promise)]
          (deliver result 0)
          (sut/build (c/set-ending {} result)))
        (is (some? (c/get-work-dir @received-conf))
            "work-dir must be present so make-routes wires podman routes")))))
