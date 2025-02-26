(ns monkey.ci.script.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-commons.byte-streams :as bs]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [protocols :as p]]
            [monkey.ci.script
             [config :as sc]
             [runtime :as sut]]
            [monkey.ci.test.aleph-test :as at]))

(def test-config (-> sc/empty-config
                     (sc/set-api {:url "http://test"
                                  :port 1234
                                  :token "test-token"})
                     (sc/set-build {:build-id "test-build"})
                     (sc/set-result (md/deferred))))

(deftest make-system
  (at/with-fake-http ["http://localhost:1234/events"
                      {:status 200
                       :body (bs/to-input-stream "")}]
    (let [sys (-> (sut/make-system test-config)
                  (co/start))]
      (testing "adds mailman"
        (is (some? (:mailman sys))))

      (testing "adds routes"
        (is (some? (:routes sys))))

      (testing "adds artifact repo"
        (is (art/repo? (:artifacts sys))))

      (testing "adds cache repo"
        (is (art/repo? (:cache sys))))

      (testing "adds event stream"
        (is (some? (:event-stream sys))))

      (let [api-client (:api-client sys)]
        (testing "adds api client"
          (is (fn? api-client)))

        (testing "api client connects to localhost"
          (at/with-fake-http ["http://localhost:1234/test" {:status 200}]
            (is (= 200 (-> (api-client {:path "/test" :request-method :get})
                           (deref)
                           :status)))))))))

(deftest run-script
  (at/with-fake-http ["http://localhost:1234/events"
                      {:status 200
                       :body (bs/to-input-stream "")}]
    (testing "returns a deferred"
      (is (md/deferred? (sut/run-script test-config))))))
