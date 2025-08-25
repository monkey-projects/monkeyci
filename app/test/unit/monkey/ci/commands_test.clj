(ns monkey.ci.commands-test
  (:require [aleph.http :as http]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.spec.alpha :as spec]
            [clojure.string :as cstr]
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
             [core :as sc]]
            [monkey.ci.spec.sidecar :as ss]
            [monkey.ci.test
             [aleph-test :as at]
             [config :as tc]
             [helpers :as h]
             [mailman :as tm]]
            [monkey.ci.web
             [crypto :as wc]
             [http :as wh]]))

(deftest run-build-local
  (testing "creates event broker and posts `build/pending` event"
    (let [broker (tm/test-component)]
      (is (md/deferred? (sut/run-build-local {:mailman broker})))
      (let [evt (-> broker
                    :broker
                    (tm/get-posted)
                    first)]
        (is (= :build/pending (:type evt)))
        (is (some? (:build evt))))))

  (let [broker (tm/test-component)]
    (is (md/deferred? (sut/run-build-local {:mailman broker
                                            :args {:workdir "/test/dir"
                                                   :dir ".script"}})))
    (let [build (-> broker
                    :broker
                    (tm/get-posted)
                    first
                    :build)]
      (testing "passes `workdir` as checkout dir and `dir` as script dir"
        (is (= "/test/dir" (:checkout-dir build)))
        (is (= ".script" (-> build
                             :script
                             :script-dir))))

      (testing "creates local data encryption key for build"
        (is (wc/b64-dek? (:dek build)))))))

(deftest args->build
  (testing "uses workdir as checkout dir"
    (is (= "/test-wd"
           (-> {:workdir "/test-wd"}
               (sut/args->build)
               :checkout-dir))))

  (testing "when no workdir, uses current dir as checkout dir"
    (is (= (u/cwd)
           (-> {}
               (sut/args->build)
               :checkout-dir))))

  (testing "contains git url"
    (is (= "http://git-url"
           (-> {:git-url "http://git-url"}
               (sut/args->build)
               :git
               :url))))

  (testing "contains commit id as ref"
    (is (= "test-ref"
           (-> {:commit-id "test-ref"}
               (sut/args->build)
               :git
               :ref))))

  (testing "contains branch"
    (is (= "test-ref"
           (-> {:branch "test-ref"}
               (sut/args->build)
               :git
               :branch))))

  (testing "contains tag"
    (is (= "test-ref"
           (-> {:tag "test-ref"}
               (sut/args->build)
               :git
               :tag))))

  (testing "sets workdir as git checkout dir"
    (is (= "/test/wd"
           (-> {:workdir "/test/wd"
                :git-url "http://test-url"}
               (sut/args->build)
               :git
               :dir))))

  #_(testing "when running from git url, uses temp dir as checkout dir"
      (is (cstr/starts-with?
           (-> {:git-url "http://test-git-url"}
               (sut/args->build)
               :checkout-dir)
           (System/getProperty "java.io.tmpdir")))))

(deftest parse-params
  (testing "empty when empty input"
    (is (= [] (sut/parse-params []))))

  (testing "splits key/value pairs by ="
    (is (= [{:name "param1" :value "value1"}
            {:name "param2" :value "value2"}]
           (sut/parse-params ["param1=value1"
                              "param2=value2"]))))

  (testing "fails on invalid param"
    (is (thrown? Exception (sut/parse-params ["invalid"]))))

  (testing "takes additional = chars"
    (is (= [{:name "key" :value "key=value"}]
           (sut/parse-params ["key=key=value"])))))

(deftest load-param-files
  (h/with-tmp-dir dir
    (testing "reads edn file"
      (let [p (str dir "/test-params.edn")]
        (is (nil? (spit p (pr-str {:test-key "test-val"}))))
        (is (= [{:name "test-key" :value "test-val"}]
               (sut/load-param-files [p])))))

    (testing "reads json file"
      (let [p (str dir "/test-params.json")]
        (is (nil? (spit p "{\"test-key\": \"test-val\"}")))
        (is (= [{:name "test-key" :value "test-val"}]
               (sut/load-param-files [p])))))

    (testing "reads yaml file"
      (let [p (str dir "/test-params.yaml")]
        (is (nil? (spit p "test-key: test-val")))
        (is (= [{:name "test-key" :value "test-val"}]
               (sut/load-param-files [p])))))

    (testing "reads java properties file"
      (let [p (str dir "/test-params.properties")]
        (is (nil? (spit p "test-key=test-val")))
        (is (= [{:name "test-key" :value "test-val"}]
               (sut/load-param-files [p])))))

    (testing "fails on unsupported file type"
      (is (thrown? Exception (sut/load-param-files ["test.txt"]))))))

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
                                 :org-id "test-cust"
                                 :repo-id "test-repo"}}}]
      (with-redefs [http/request (constantly (md/success-deferred {:body (pr-str builds)}))]
        (is (some? (sut/list-builds rt)))
        (is (pos? (count @reported)))
        (let [r (first @reported)]
          (is (= :build/list (:type r)))
          (is (= builds (:builds r))))))))

(deftest http-server
  (with-redefs [http/start-server (constantly (reify java.lang.AutoCloseable
                                                (close [this] nil)))
                wh/on-server-close (constantly (md/success-deferred ::done))]
    (testing "starts the server and waits for close"
      (let [r (sut/http-server tc/app-config)]
        (is (= ::done r))))))

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
