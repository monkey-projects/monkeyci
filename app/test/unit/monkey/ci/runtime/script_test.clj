(ns monkey.ci.runtime.script-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-commons.byte-streams :as bs]
            [monkey.ci
             [artifacts :as art]
             [protocols :as p]]
            [monkey.ci.config.script :as cs]
            [monkey.ci.runtime.script :as sut]
            [monkey.ci.test.aleph-test :as at]))

(def test-config (-> cs/empty-config
                     (cs/set-api {:url "http://test"
                                  :port 1234
                                  :token "test-token"})
                     (cs/set-build {:build-id "test-build"})))

(deftest make-system
  (testing "creates system map with runtime"
    (is (sut/runtime? (:runtime (sut/make-system test-config))))))

(deftest with-runtime
  (at/with-fake-http ["http://localhost:1234/events"
                      {:status 200
                       :body (bs/to-input-stream "")}]
    (testing "invokes target function with runtime"
      (is (= ::invoked (sut/with-runtime test-config (constantly ::invoked)))))

    (testing "drops workspace from runtime"
      (is (nil? (sut/with-runtime test-config :workspace))))

    (testing "adds artifact repo"
      (is (art/repo? (sut/with-runtime
                       test-config
                       :artifacts))))

    (testing "adds cache repo"
      (is (art/repo? (sut/with-runtime
                       test-config
                       :cache))))

    (testing "adds container runner"
      (is (p/container-runner? (sut/with-runtime
                                 test-config
                                 :containers))))

    (testing "adds event bus"
      (is (some? (sut/with-runtime
                   test-config
                   :event-bus))))

    (let [api-client (sut/with-runtime test-config (comp :client :api))]
      (testing "adds api client"
        (is (fn? api-client)))

      (testing "api client connects to localhost"
        (at/with-fake-http ["http://localhost:1234/test" {:status 200}]
          (is (= 200 (-> (api-client {:path "/test" :request-method :get})
                         (deref)
                         :status))))))))
