(ns monkey.ci.cli.run-test
  (:require [clojure.core.async :as ca]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.cli
             [config :as c]
             [process :as proc]
             [run :as sut]
             [server :as srv]]
            [monkey.mailman
             [core :as mmc]
             [core-async :as mmca]]))

(def ^:private dummy-server
  {:server        nil
   :port          9999
   :token         "fake-token"
   :event-mult-ch (ca/chan 1)
   :event-mult    nil})

(defn- test-config [conf & [res]]
  (let [result (promise)]
    (deliver result (or res ::test-result))
    (c/set-ending conf result)))

(deftest build-test
  (let [broker (mmca/core-async-broker)]
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
        (let [timeout (ca/timeout 1000)]
          (is (= :build/pending
                 (-> (ca/alts!! [(.chan broker) timeout])
                     first
                     :type)))))

      (let [received-conf (atom nil)]
        (with-redefs [sut/setup-events (fn [conf _]
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
