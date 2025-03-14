(ns monkey.ci.runtime.app-test
  (:require [aleph.http :as aleph]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [oci :as oci]
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.runtime.app :as sut]
            [monkey.ci.test.config :as tc]))

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

      (testing "provides metrics routes"
        (is (some? (:metrics-routes sys))))

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
    (with-redefs [oci/delete-stale-instances (fn [ctx cid]
                                               {:context ctx
                                                :compartment-id cid})]
      (testing "for `:oci` runners"
        (let [r (sut/->ProcessReaper {:runner
                                      {:type :oci
                                       :containers {:user-ocid "test-user"
                                                    :compartment-id "test-comp"}}})
              res (r)]
          (testing "creates context from container config"
            (is (some? (:context res)))
            (is (= "test-comp" (:compartment-id res))))))

      (testing "not for other runners"
        (let [r (sut/->ProcessReaper {:runner {:type :some-other}})]
          (is (empty? (r))))))))

