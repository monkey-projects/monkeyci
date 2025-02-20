(ns monkey.ci.local.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as a]
             [protocols :as p]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.local
             [config :as lc]
             [runtime :as sut]]
            [monkey.mailman.core :as mmc]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.mailman :as tm]))

(deftest start-and-post
  (h/with-tmp-dir dir
    (let [broker (tm/test-component)
          evt {:type :test}
          r (-> {:mailman broker}
                (lc/set-work-dir dir)
                (lc/set-build (h/gen-build))
                (sut/start-and-post evt))]
      (testing "returns deferred"
        (is (md/deferred? r)))

      (testing "posts event to broker"
        (is (= [evt] (-> broker
                         :broker
                         (tm/get-posted))))))))

(deftest make-system
  (h/with-tmp-dir dir
    (let [sys (-> {}
                  (lc/set-work-dir dir)
                  (sut/make-system))]
      (testing "has mailman"
        (is (some? (:mailman sys))))

      (testing "has artifacts"
        (is (p/blob-store? (:artifacts sys))))

      (testing "has cache"
        (is (p/blob-store? (:cache sys))))

      (testing "has build params"
        (is (satisfies? p/BuildParams (:params sys))))

      (testing "has containers"
        (is (p/container-runner? (:containers sys))))

      (testing "has api server"
        (is (some? (:api-server sys))))

      (testing "has mailman routes"
        (is (some? (:routes sys))))

      (testing "when container build"
        (testing "has workspace")))))

(defrecord TestListener [unreg?]
  mmc/Listener
  (unregister-listener [this]
    (reset! unreg? true)))

(deftest routes
  (let [mailman (-> (em/make-component {:type :manifold})
                    (co/start))]
    (testing "`start` registers a listener"
      (is (some? (-> (sut/->Routes [] mailman)
                     (co/start)
                     :listener))))

    (testing "`stop` unregisters listener"
      (let [unreg? (atom false)]
        (is (nil? (-> (sut/map->Routes {:listener (->TestListener unreg?)})
                      (co/stop)
                      :listener)))
        (is (true? @unreg?))))))
