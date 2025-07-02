(ns monkey.ci.runtime.app-test
  (:require [aleph.http :as aleph]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [oci :as oci]
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.runtime.app :as sut]
            [monkey.ci.vault.scw :as v-scw]
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

      (testing "provides http app"
        (is (some? (:http-app sys))))

      (testing "runtime"
        (let [rt (get-in sys [:http-app :runtime])]
          (testing "http app has runtime"
            (is (map? rt)))

          (testing "runtime has storage"
            (is (satisfies? p/Storage (:storage rt))))

          (testing "provides empty jwk if not configured"
            (is (nil? (:jwk rt))))

          (testing "provides metrics"
            (is (some? (get sys :metrics)))
            (is (some? (:metrics rt))))

          (testing "provides process reaper"
            (is (ifn? (:process-reaper rt))))

          (testing "provides vault"
            (is (p/vault? (:vault rt))))

          (testing "provides data encryption key generator"
            (is (fn? (:dek-generator rt))))

          (testing "provides encrypter"
            (is (fn? (:encrypter rt))))

          (testing "provides decrypter"
            (is (fn? (:decrypter rt))))))

      (testing "provides metrics routes"
        (is (some? (:metrics-routes sys))))

      (testing "provides mailman"
        (is (some? (:mailman sys))))

      (testing "provides mailman routes"
        (is (some? (:mailman-routes sys)))
        (is (some? (-> sys :mailman-routes :options))
            "has queue options"))

      (testing "provides update bus"
        (is (some? (:update-bus sys))))

      (testing "provides queue options"
        (is (some? (:queue-options sys)))))))

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

(deftest queue-options
  (testing "`jms` configures destinations"
    (is (map? (-> {:mailman
                   {:type :jms}}
                  (sut/new-queue-options)
                  :destinations))))

  (testing "`nats`"
    (testing "configures queue"
      (is (= "test-queue"
             (-> {:mailman
                  {:type :nats
                   :queue "test-queue"}}
                 (sut/new-queue-options)
                 :queue))))

    (testing "configures stream and consumer"
      (is (= {:stream "test-stream"
              :consumer "test-consumer"}
             (-> {:mailman
                  {:type :nats
                   :db {:stream "test-stream"
                        :consumer "test-consumer"}}}
                 (sut/new-queue-options)))))))

(deftest dek-utils
  (testing "default key generator"
    (let [u (sut/dek-utils {})]
      (testing "constantly nil"
        (is (nil? ((:generator u)))))))

  (testing "scaleway"
    (with-redefs [v-scw/generate-dek (constantly (md/success-deferred "new-dek"))]
      (let [{:keys [generator decrypter]} (sut/dek-utils {:type :scw})]
        (testing "provides generator function"
          (is (fn? generator))
          (is (= "new-dek" (generator))))

        (testing "provides key decrypter"
          (is (fn? decrypter)))))))
