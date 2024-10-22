(ns monkey.ci.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci
             [commands :as sut]
             [edn :as edn]
             [errors :as err]
             [runners :as r]
             [sidecar :as sc]]
            [monkey.ci.config.sidecar :as cs]
            [monkey.ci.helpers :as h]
            [monkey.ci.spec.sidecar :as ss]
            [monkey.ci.web.handler :as wh]
            [monkey.ci.test
             [config :as tc]
             [aleph-test :as at]]))

(defmethod r/make-runner ::dummy [_]
  (constantly :invoked))

(defmethod r/make-runner ::build [_]
  (fn [build _]
    build))

(defmethod r/make-runner ::failing [_]
  (fn [& _]
    (throw (ex-info "test error" {}))))

(deftest run-build
  (let [config (assoc tc/base-config
                      :build {:build-id "test-build"})]
    (testing "invokes runner from context"
      (is (= :invoked (sut/run-build (assoc config :runner {:type ::dummy})))))

    (testing "adds `build` to runtime"
      (is (map? (-> config
                    (assoc :args {:git-url "test-url"
                                  :branch "test-branch"
                                  :commit-id "test-id"}
                           :runner {:type ::build})
                    (sut/run-build)))))

    (testing "posts `build/end` event on exception"
      (let [recv (atom [])]
        (is (= err/error-process-failure
               (-> config
                   (assoc :runner {:type ::failing}
                          :events {:type :fake
                                   :recv recv})
                   (sut/run-build))))
        (is (= 1 (count @recv)))
        (let [evt (first @recv)]
          (is (= :build/end (:type evt)))
          (is (some? (:build evt))))))))

(deftest verify-build
  (testing "zero when successful"
    (is (zero? (sut/verify-build {:work-dir "examples"
                                  :args {:dir "basic-clj"}}))))
  
  (testing "nonzero exit on failure"
    (is (not= 0 (sut/verify-build {})))))

(deftest list-builds
  (testing "reports builds from server"
    (let [reported (atom [])
          builds {:key "value"}
          rt {:reporter (partial swap! reported conj)
              :config {:account {:url "http://server/api"
                                 :customer-id "test-cust"
                                 :repo-id "test-repo"}}}]
      (with-redefs [http/request (constantly (md/success-deferred {:body (pr-str builds)}))]
        (is (some? (sut/list-builds rt)))
        (is (pos? (count @reported)))
        (let [r (first @reported)]
          (is (= :build/list (:type r)))
          (is (= builds (:builds r))))))))

(deftest http-server
  (with-redefs [wh/on-server-close (constantly (md/success-deferred nil))]
    (testing "starts the server and waits for close"
      (let [r (sut/http-server tc/base-config)]
        (is (nil? (deref r 100 :timeout)))))))

(deftest watch
  (testing "sends request and returns deferred"
    (with-redefs [http/get (constantly (md/success-deferred nil))]
      (is (md/deferred? (sut/watch {})))))

  (testing "reports received events from reader"
    (let [events (prn-str {:type :script/started
                           :message "Test event"})
          reported (atom [])]
      (with-redefs [http/get (constantly (md/success-deferred {:body (bs/to-reader events)}))]
        (is (md/deferred? (sut/watch {:reporter (partial swap! reported conj)})))
        (is (not-empty @reported))))))

(deftest sidecar
  (let [inv-args (atom nil)]
    (with-redefs [sc/run (fn [args]
                           (reset! inv-args args)
                           (md/success-deferred {:exit ::test-exit}))]
      (letfn [(validate-sidecar [config job recv]
                (let [result (sut/sidecar config)]
                  (testing "runs sidecar poll loop, returns exit code"
                    (is (= ::test-exit result)))

                  (testing "passes file paths from args"
                    (is (= "test-events" (get-in @inv-args [:paths :events-file]))))

                  (testing "posts start and end events"
                    (is (= [:sidecar/start :sidecar/end]
                           (map :type (recv)))))

                  (testing "events contain job id from config"
                    (is (= (:id job) (-> (recv)
                                         first
                                         :job-id))))))]
        
        (testing "from sidecar-specific config"
          (let [job {:id (str (random-uuid))}
                config (-> {}
                           (cs/set-events-file "test-events")
                           (cs/set-start-file "start")
                           (cs/set-abort-file "abort")
                           (cs/set-api {:url "http://test"
                                        :token (str (random-uuid))})
                           (cs/set-job job)
                           (cs/set-build {:build-id "test-build"
                                          :workspace "test-ws"}))
                recv (atom [])]
            (is (spec/valid? ::ss/config config)
                (spec/explain-str ::ss/config config))
            (at/with-fake-http ["http://test/events" (fn [req]
                                                       (swap! recv concat (edn/edn-> (:body req)))
                                                       (md/success-deferred {:status 200}))]
              (validate-sidecar config job (fn [] @recv)))))))))
