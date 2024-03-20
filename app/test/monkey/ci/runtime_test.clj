(ns monkey.ci.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as c]
             [containers]
             [logging]
             [runners]
             [runtime :as sut]
             [storage]
             [workspace]]
            [monkey.ci.events.core]
            [monkey.ci.web.handler]))

(defn- verify-runtime [k extra-config checker]
  (is (checker (-> c/default-app-config
                   (assoc k extra-config)
                   (sut/config->runtime)
                   k))))

(deftest config->runtime
  (testing "creates default runtime from empty config"
    (is (map? (sut/config->runtime c/default-app-config))))

  (testing "adds original config"
    (let [conf c/default-app-config]
      (is (= conf (:config (sut/config->runtime conf))))))

  (testing "provides log maker"
    (is (fn? (-> c/default-app-config
                 (assoc :logging {:type :file
                                  :dir "/tmp"})
                 (sut/config->runtime)
                 :logging
                 :maker))))

  (testing "provides log retriever"
    (is (some? (-> c/default-app-config
                   (assoc :logging {:type :file
                                    :dir "/tmp"})
                   (sut/config->runtime)
                   :logging
                   :retriever))))
  
  (testing "provides runner"
    (verify-runtime :runner {:type :child} fn?))

  (testing "provides storage"
    (verify-runtime :storage {:type :memory} some?))

  (testing "provides container runner"
    (verify-runtime :containers {:type :podman} some?))

  (testing "provides workspace"
    (verify-runtime :workspace {:type :disk :dir "/tmp"} some?))

  (testing "provides artifacts"
    (verify-runtime :artifacts {:type :disk :dir "/tmp"} some?))

  (testing "provides cache"
    (verify-runtime :cache {:type :disk :dir "/tmp"} some?))

  (testing "provides events"
    (verify-runtime :events {:type :manifold} some?))

  (testing "provides http server"
    (verify-runtime :http {:port 3000} fn?)))

(deftest from-config
  (testing "gets value from config"
    (is (= "test-val" ((sut/from-config :test-val)
                       {:config {:test-val "test-val"}})))))

(deftest rt->env
  (testing "returns config"
    (is (= {:key "value"}
           (sut/rt->env {:config {:key "value"}}))))

  (testing "removes event server"
    (is (nil? (-> (sut/rt->env {:config {:events {:type :zmq
                                                  :server {:enabled true}}}})
                  :events
                  :server)))))

(defrecord TestComponent [started? stopped?]
  co/Lifecycle
  (start [this]
    (reset! started? true)
    this)
  (stop [this]
    (reset! stopped? true)
    this))

(deftest with-runtime
  (testing "starts and stops components"
    (with-redefs [sut/config->runtime (constantly {:test (->TestComponent (atom false) (atom false))})]
      (is (= [true true]
             (->> (sut/with-runtime {} :test rt
                    (md/success-deferred (:test rt)))
                  ((juxt :started? :stopped?))
                  (map deref)))))))
