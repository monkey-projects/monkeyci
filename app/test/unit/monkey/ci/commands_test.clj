(ns monkey.ci.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci
             [commands :as sut]
             [edn :as edn]
             [errors :as err]
             [pem :as pem]
             [process :as proc]
             [runners :as r]
             [sidecar :as sc]]
            [monkey.ci.config.sidecar :as cs]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.helpers :as h]
            [monkey.ci.runners.controller :as rc]
            [monkey.ci.spec
             [build :as sb]
             [sidecar :as ss]]
            [monkey.ci.web.handler :as wh]
            [monkey.ci.test
             [config :as tc]
             [aleph-test :as at]
             [mailman :as tm]]))

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

(deftest run-build-local
  (testing "creates event broker and posts `build/pending` event"
    (let [broker (tm/test-component)]
      (is (md/deferred? (sut/run-build-local {:mailman broker})))
      (let [evt (-> broker
                    :broker
                    (tm/get-posted)
                    first)]
        (is (= :build/pending (:type evt)))
        (is (some? (:build evt)))))))

(deftest verify-build
  (testing "zero when successful"
    (is (zero? (sut/verify-build {:work-dir "examples"
                                  :args {:dir "basic-clj"}}))))
  
  (testing "nonzero exit on failure"
    (is (not= 0 (sut/verify-build {})))))

(deftest run-tests
  (testing "runs test process with build"
    (let [inv (atom nil)]
      (with-redefs [proc/test! (fn [build _]
                                 (reset! inv {:build build
                                              :invoked? true})
                                 (future {:exit 0}))]
        (let [res (sut/run-tests {})]
          (is (zero? res))
          (is (some? (:build @inv)))
          (is (true? (:invoked? @inv))))))))

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
      (let [r (sut/http-server tc/app-config)]
        (is (nil? r))))))

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

(deftest controller
  (testing "invokes `run-controller` with runtime created from config"
    (with-redefs [rc/run-controller (fn [rt]
                                       (when (some? rt) ::ok))]
      (is (= ::ok (sut/controller tc/base-config))))))

(deftest issue-creds
  (testing "sends http request to api endpoint"
    (h/with-tmp-dir dir
      (at/with-fake-http [{:url "http://test/admin/credits/issue"
                           :request-method :post}
                          {:status 200}]
        (let [pk (h/generate-private-key)
              pk-file (str (fs/path dir "test-key"))]
          (is (nil? (spit pk-file (pem/private-key->pem pk))))
          (is (zero?
               (sut/issue-creds {:args
                                 {:all true
                                  :username "testuser"
                                  :private-key pk-file
                                  :api "http://test"}}))))))))
