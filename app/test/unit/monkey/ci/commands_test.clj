(ns monkey.ci.commands-test
  (:require [aleph.http :as http]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [monkey.ci
             [commands :as sut]
             [cuid :as cuid]
             [edn :as edn]
             [pem :as pem]
             [process :as proc]
             [utils :as u]]
            [monkey.ci.runners.controller :as rc]
            [monkey.ci.sidecar
             [config :as cs]
             [core :as sc]
             [spec :as ss]]
            [monkey.ci.test
             [aleph-test :as at]
             [config :as tc]
             [helpers :as h]
             [mailman :as tm]]
            [monkey.ci.web
             [crypto :as wc]
             [http :as wh]]))

(deftest http-server
  (with-redefs [http/start-server (constantly (reify java.lang.AutoCloseable
                                                (close [this] nil)))
                wh/on-server-close (constantly (md/success-deferred ::done))]
    (testing "starts the server and waits for close"
      (let [r (sut/http-server tc/app-config)]
        (is (= ::done r))))))

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
                                         :job-id))))

                  (testing "events contain sid id from config"
                    (is (= (cs/sid config)
                           (-> (recv)
                               first
                               :sid))))))]
        
        (testing "from sidecar-specific config"
          (let [job {:id (str (random-uuid))}
                config (-> {}
                           (cs/set-events-file "test-events")
                           (cs/set-start-file "start")
                           (cs/set-abort-file "abort")
                           (cs/set-api {:url "http://test"
                                        :token (str (random-uuid))})
                           (cs/set-job job)
                           (cs/set-sid (repeatedly 3 cuid/random-cuid)))
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
  (h/with-tmp-dir dir
    (at/with-fake-http [{:url "http://test/admin/credits/issue"
                         :request-method :post}
                        {:status 200}]
      (let [pk (h/generate-private-key)
            pk-file (str (fs/path dir "test-key"))]
        (is (nil? (spit pk-file (pem/private-key->pem pk))))

        (testing "sends http request to api endpoint"
          (is (zero?
               (sut/issue-creds
                {:args
                 {:all true
                  :username "testuser"
                  :private-key pk-file
                  :api "http://test"}}))))

        (testing "merges config with args"
          (is (zero?
               (sut/issue-creds
                {:issue-creds
                 {:api "http://test"}
                 :args
                 {:all true
                  :username "testuser"
                  :private-key pk-file}}))))))))

(deftest cancel-dangling-builds
  (h/with-tmp-dir dir
    (at/with-fake-http [{:url "http://test/admin/reaper"
                         :request-method :post}
                        {:status 200}]
      (let [pk (h/generate-private-key)
            pk-file (str (fs/path dir "test-key"))]
        (is (nil? (spit pk-file (pem/private-key->pem pk))))

        (testing "sends http request to api endpoint"
          (is (zero?
               (sut/cancel-dangling-builds
                {:args
                 {:username "testuser"
                  :private-key pk-file
                  :api "http://test"}}))))

        (testing "merges config with args"
          (is (zero?
               (sut/cancel-dangling-builds
                {:dangling-builds
                 {:api "http://test"
                  :private-key pk}
                 :args
                 {:username "testuser"}}))))))))
