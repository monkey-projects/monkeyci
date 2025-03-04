(ns monkey.ci.runtime.app-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as aleph]
            [clojure.spec.alpha :as spec]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [metrics :as m]
             [oci :as oci]
             [runtime :as rt]
             [storage :as st]
             [prometheus :as prom]
             [protocols :as p]]
            [monkey.ci.containers.oci :as cco]
            [monkey.ci.runtime.app :as sut]
            [monkey.ci.spec.runner :as sr]
            [monkey.ci.test.config :as tc]
            [monkey.ci.helpers :as h]))

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

      (testing "provides metrics"
        (is (some? (get sys :metrics)))
        (is (some? (get-in sys [:http :rt :metrics]))))

      (testing "provides process reaper in runtime"
        (is (ifn? (get-in sys [:http :rt :process-reaper]))))

      (testing "provides vault in runtime"
        (is (p/vault? (get-in sys [:http :rt :vault]))))

      (testing "provides mailman"
        (is (some? (:mailman sys))))

      (testing "provides mailman routes"
        (is (some? (:mailman-routes sys))))

      (testing "provides update bus"
        (is (some? (:update-bus sys)))))))

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

(deftest app-event-routes-component
  (testing "`start` creates routes"
    (is (not-empty (-> (sut/new-app-routes)
                       (assoc :storage (st/make-memory-storage))
                       (co/start)
                       :routes)))))
