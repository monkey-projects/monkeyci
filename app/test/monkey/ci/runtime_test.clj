(ns monkey.ci.runtime-test
  (:require [monkey.ci
             [config :as c]
             [containers]
             [logging]
             [runners]
             [runtime :as sut]
             [storage]
             [workspace]]
            [monkey.ci.events
             [core]
             [manifold]]
            [monkey.ci.web.handler]
            [clojure.test :refer [deftest testing is]]))

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
           (sut/rt->env {:config {:key "value"}})))))
