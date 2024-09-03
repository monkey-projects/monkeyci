(ns monkey.ci.containers.build-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci.build.api :as api]
            [monkey.ci
             [containers :as mcc]
             [protocols :as p]]
            [monkey.ci.containers.build-api :as sut]
            [monkey.ci.test.aleph-test :as at]))

(deftest run-container
  (let [invocations (atom [])
        client (api/make-client "http://test-api" "test-token")
        runner (sut/->BuildApiContainerRunner client)
        job {:id "test-job"}]

    (testing "invokes endpoint on api server"
      (at/with-fake-http [{:url "http://test-api/container"
                           :request-method :post}
                          (fn [req]
                            (swap! invocations conj req)
                            {:status 202})]
        (is (md/deferred? (p/run-container runner job)))
        (is (= 1 (count @invocations)))))

    (testing "fails on api call error"
      (at/with-fake-http ["http://test-api/container" (fn [_]
                                                        (throw (ex-info "test error" {})))]
        (is (thrown? Exception (deref (p/run-container runner job) 1000 ::timeout)))))

    (testing "reads events until sidecar/end and job/end have been received")))
