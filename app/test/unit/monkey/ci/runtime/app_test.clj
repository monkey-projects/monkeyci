(ns monkey.ci.runtime.app-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as aleph]
            [clojure.spec.alpha :as spec]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [metrics :as m]
             [oci :as oci]
             [runtime :as rt]
             [prometheus :as prom]
             [protocols :as p]]
            [monkey.ci.containers.oci :as cco]
            [monkey.ci.runtime.app :as sut]
            [monkey.ci.spec.runner :as sr]
            [monkey.ci.test.config :as tc]
            [monkey.ci.helpers :as h])
  (:import monkey.ci.listeners.Listeners))

(def runner-config
  (assoc tc/base-config
         :checkout-base-dir "/base/dir"
         :build {:customer-id "test-cust"
                 :repo-id "test-repo"
                 :build-id "test-build"
                 :sid ["test" "build"]}
         :api {:url "http://test"
               :token "test-token"}))

(deftest make-runner-system
  (let [sys (sut/make-runner-system runner-config)]
    (testing "creates system map"
      (is (map? sys)))

    (testing "contains runtime"
      (is (sut/runtime? (:runtime sys))))

    (testing "generates api token and random port"
      (let [conf (:api-config sys)]
        (is (string? (:token conf)))
        (is (int? (:port conf)))))))

(deftest with-runner-runtime
  (let [sys (sut/with-runner-system runner-config identity)
        rt (:runtime sys)]
    
    (testing "runtime matches spec"
      (is (spec/valid? ::sr/runtime rt)
          (spec/explain-str ::sr/runtime rt)))
    
    (testing "provides events"
      (is (some? (:events rt))))

    (testing "provides artifacts"
      (is (some? (:artifacts rt))))

    (testing "provides cache"
      (is (some? (:cache rt))))

    (testing "provides build cache"
      (is (some? (:build-cache rt))))

    (testing "provides containers"
      (is (some? (:containers rt))))

    (testing "passes api config to oci container runner"
      (let [sys (-> runner-config
                    (assoc :containers {:type :oci})
                    (sut/with-runner-system identity))]
        (with-redefs [cco/run-container :api]
          (is (= (get-in sys [:api-config :token])
                 (:token (p/run-container (:containers sys) {})))))))

    (testing "provides runner"
      (is (ifn? (:runner sys))))

    (testing "provides workspace"
      (is (some? (:workspace rt))))

    (testing "provides logging"
      (is (some? (rt/log-maker rt))))

    (testing "provides git"
      (is (fn? (get-in rt [:git :clone]))))

    (testing "adds config"
      (is (= runner-config (:config rt))))

    (testing "build"
      (let [build (:build rt)]
        (testing "sets workspace path"
          (is (string? (:workspace build))))

        (testing "calculates checkout dir"
          (is (= "/base/dir/test-build" (:checkout-dir build))))))

    (testing "starts api server at configured pod"
      (is (some? (get-in sys [:api-server :server])))
      (is (= (get-in sys [:api-server :port])
             (get-in sys [:api-config :port]))))

    (testing "system has metrics"
      (is (some? (:metrics sys))))

    (testing "has push gateway"
      (is (some? (:push-gw sys))))))

(deftest push-gw
  (let [conf {:push-gw
              {:host "test-host"
               :port 9091}}
        sys (-> (co/system-map
                 :push-gw (co/using (sut/new-push-gw conf) [:metrics])
                 :metrics (m/make-metrics))
                (co/start))
        p (:push-gw sys)
        pushed? (atom false)]
    (with-redefs [prom/push (fn [_] (reset! pushed? true))]
      (try
        (testing "creates push gw on start"
          (is (some? (:gw p))))
        (finally (co/stop sys))))

    (testing "pushes metrics on system stop"
      (is (true? @pushed?)))))

(def server-config
  (assoc tc/base-config
         :http {:port 3001}
         :vault {:type :noop}))

(defn fake-server []
  (reify java.lang.AutoCloseable
    (close [this])))

(deftest with-server-system
  (with-redefs [aleph/start-server (constantly (fake-server))]
    (let [sys (sut/with-server-system server-config identity)]
      (testing "provides http server"
        (is (some? (:http sys)))
        (is (some? (get-in sys [:http :server]))))

      (testing "http server has runtime"
        (is (map? (get-in sys [:http :rt]))))

      (testing "runtime has storage"
        (is (satisfies? p/Storage (get-in sys [:http :rt :storage]))))

      (testing "provides empty jwk if not configured"
        (is (nil? (get-in sys [:http :rt :jwk]))))

      (testing "activates listeners"
        (is (instance? monkey.ci.listeners.Listeners (get-in sys [:listeners :listeners]))))

      (testing "passes events to listeners"
        (is (some? (-> sys :listeners :listeners :events))))

      (testing "passes listener events if configured"
        (is (= ::listener-events
               (-> server-config
                   (assoc :listeners {:events {:type :fake
                                               ::kind ::listener-events}})
                   (sut/with-server-system :listeners)
                   :listeners
                   :events
                   :in
                   :config
                   ::kind))))

      (testing "provides metrics"
        (is (some? (get sys :metrics)))
        (is (some? (get-in sys [:http :rt :metrics]))))

      (testing "provides process reaper in runtime"
        (is (ifn? (get-in sys [:http :rt :process-reaper]))))

      (testing "provides vault in runtime"
        (is (p/vault? (get-in sys [:http :rt :vault])))))))

(deftest process-reaper
  (testing "returns empty list when no oci runner"
    (let [r (sut/->ProcessReaper {:runner {:type :local}})]
      (is (empty? (r)))))

  (testing "deletes oci stale instances"
    (with-redefs [oci/delete-stale-instances (constantly ::ok)]
      (testing "for `:oci` runners"
        (let [r (sut/->ProcessReaper {:runner {:type :oci}})]
          (is (= ::ok (r)))))

      (testing "for `:oci2` runners"
        (let [r (sut/->ProcessReaper {:runner {:type :oci2}})]
          (is (= ::ok (r)))))

      (testing "not for other runners"
        (let [r (sut/->ProcessReaper {:runner {:type :some-other}})]
          (is (empty? (r))))))))
