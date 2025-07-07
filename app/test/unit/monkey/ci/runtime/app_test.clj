(ns monkey.ci.runtime.app-test
  (:require [aleph.http :as aleph]
            [buddy.core.codecs :as bcc]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [cuid :as cuid]
             [oci :as oci]
             [protocols :as p]
             [storage :as st]
             [utils :as u]
             [vault :as v]]
            [monkey.ci.runtime.app :as sut]
            [monkey.ci.vault
             [common :as vc]
             [scw :as v-scw]]
            [monkey.ci.web.crypto :as crypto]
            [monkey.ci.test
             [config :as tc]
             [helpers :as h]]))

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

(deftest crypto
  (h/with-memory-store st
    (let [k {:enc "encrypted-dek"
             :key (bcc/bytes->b64 (v/generate-key))}]
      (defmethod sut/dek-utils ::test [_]
        {:generator (constantly k)
         :decrypter (fn [v]
                      (when (= (:enc k) v)
                        (:key k)))})

      (let [cache (atom {})
            org-id (cuid/random-cuid)
            crypto (-> (sut/new-crypto {:dek
                                        {:type ::test}})
                       (assoc :cache cache
                              :storage st)
                       (co/start))
            encrypt (fn [v]
                      (vc/encrypt (bcc/b64->bytes (:key k))
                                  (v/cuid->iv org-id)
                                  v))]

        (testing "`generator`"
          (testing "provides dek generator fn"
            (is (fn? (:dek-generator crypto))))

          (testing "stores decrypted key in cache"
            (let [res ((:dek-generator crypto) org-id)]
              (is (string? (:enc res)))
              (is (crypto/dek? (bcc/b64->bytes (:key res))))
              (is (= [org-id] (keys @cache)))
              (is (some? (st/save-crypto st {:org-id org-id
                                             :dek (:enc res)}))))))

        (testing "`encrypter`"
          (let [e (:encrypter crypto)
                v "value to encrypt"
                validate (fn []
                           (= (encrypt v)
                              (e v org-id)))]
            (is (fn? e))
            
            (testing "encrypts value using cached key"
              (is true? (validate)))

            (testing "looks up and decrypts stored key"
              (is (empty? (reset! cache {})))
              (is (true? (validate)))
              (is (not-empty @cache)))))

        (testing "`decrypter`"
          (let [d (:decrypter crypto)
                v (encrypt "value to decrypt")
                validate (fn []
                           (= (vc/decrypt (bcc/b64->bytes (:key k))
                                          (v/cuid->iv org-id)
                                          v)
                              (d v org-id)))]
            (is (fn? d))
            
            (testing "decrypts value using cached key"
              (is true? (validate)))

            (testing "looks up and decrypts stored key"
              (is (empty? (reset! cache {})))
              (is (true? (validate)))
              (is (not-empty @cache))))))))

  (is (some? (remove-method sut/dek-utils ::test))))
