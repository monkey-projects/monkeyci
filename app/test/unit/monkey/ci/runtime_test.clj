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
            [monkey.ci.helpers :as h]
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

(deftest rt->config
  (testing "returns config"
    (is (= {:key "value"}
           (sut/rt->config {:config {:key "value"}}))))

  (testing "removes event server"
    (is (nil? (-> (sut/rt->config {:config {:events {:type :zmq
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

(deftest post-events
  (letfn [(verify-time [evt checker]
            (let [{:keys [recv] :as e} (h/fake-events)]
              (is (some? (sut/post-events {:events e} evt)))
              (is (checker (-> @recv
                               first
                               :time)))))]
    
    (testing "adds time"
      (is (verify-time {:type :test-event} number?)))

    (testing "keeps provided time"
      (is (verify-time {:type :test-event :time 100} (partial = 100))))))
