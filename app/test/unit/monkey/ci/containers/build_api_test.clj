(ns monkey.ci.containers.build-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.build
             [api :as api]
             [core :as bc]]
            [monkey.ci
             [containers :as mcc]
             [edn :as edn]
             [protocols :as p]]
            [monkey.ci.containers.build-api :as sut]
            [monkey.ci.test.aleph-test :as at]))

(deftest run-container
  (let [invocations (atom [])
        client (api/make-client "http://test-api" "test-token")
        runner (sut/->BuildApiContainerRunner client)
        job (bc/container-job "test-job"
                              {:image "test-img"
                               :script ["test-cmd"]
                               :work-dir "test/dir"})]

    (testing "invokes endpoint on api server with serialized job"
      (at/with-fake-http [{:url "http://test-api/container"
                           :request-method :post}
                          (fn [req]
                            (swap! invocations conj req)
                            {:status 202})]
        (is (md/deferred? (p/run-container runner job)))
        (is (= 1 (count @invocations)))
        (let [ser (-> @invocations
                      first
                      :body
                      edn/edn->
                      :job)]
          (is (= "test-img" (:image ser)))
          (is (= "test/dir" (:work-dir ser))))))

    (testing "fails on api call error"
      (at/with-fake-http ["http://test-api/container" (fn [_]
                                                        (throw (ex-info "test error" {})))]
        (is (thrown? Exception (deref (p/run-container runner job) 1000 ::timeout)))))

    (testing "reads events until `container-job/end` event has been received"
      (at/with-fake-http ["http://test-api/container"
                          (fn [req]
                            (swap! invocations conj req)
                            {:status 202})
                          "http://test-api/events"
                          {:status 200
                           :body (-> (str "data: " (pr-str {:type :container-job/end
                                                            :job-id (:id job)
                                                            :result ::test-result})
                                          "\n\n")
                                     (.getBytes)
                                     (io/input-stream))}]
        (let [r (p/run-container runner job)]
          (is (md/deferred? r))
          (is (= ::test-result (deref r 1000 ::timeout))))))))
