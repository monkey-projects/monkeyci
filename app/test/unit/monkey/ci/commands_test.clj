(ns monkey.ci.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci
             [commands :as sut]
             [sidecar :as sc]
             [spec :as spec]]
            [monkey.ci.helpers :as h]
            [monkey.ci.web.handler :as wh]))

(deftest run-build
  (testing "invokes runner from context"
    (let [ctx {:runner (constantly :invoked)}]
      (is (= :invoked (sut/run-build ctx)))))

  (letfn [(test-runner [build _] build)]
    (testing "adds `build` to runtime"
      (is (map? (-> {:config {:args {:git-url "test-url"
                                     :branch "test-branch"
                                     :commit-id "test-id"}}
                     :runner test-runner}
                    (sut/run-build)))))

    (testing "adds build sid to build config"
      (let [{:keys [sid build-id]} (-> {:config {:args {:sid "a/b/c"}}
                                        :runner test-runner}
                                       (sut/run-build))]
        (is (= build-id (last sid)))
        (is (= ["a" "b" "c"] (take 3 sid)))))

    (testing "constructs `sid` from account settings if not specified"
      (let [{:keys [sid build-id]} (-> {:runner test-runner
                                        :config {:account {:customer-id "a"
                                                           :repo-id "b"}}}
                                       (sut/run-build))]
        (is (= build-id (last sid)))
        (is (= ["a" "b"] (take 2 sid))))))

  (testing "posts `build/end` event on exception"
    (let [{:keys [recv] :as e} (h/fake-events)]
      (is (= 1 (-> {:runner (fn [_] (throw (ex-info "test error" {})))
                    :events e}
                   (sut/run-build))))
      (is (= 1 (count @recv)))      
      (is (= :build/end (:type (first @recv)))))))

(deftest verify-build
  (testing "zero when successful"
    (is (zero? (sut/verify-build {:config
                                  {:work-dir "examples"
                                   :args {:dir "basic-clj"}}}))))

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
    (testing "returns a deferred"
      (is (md/deferred? (sut/http-server {:http (constantly ::ok)}))))

    (testing "starts the server using the runtime fn"
      (let [r (sut/http-server {:http (constantly ::ok)})]
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
  (with-redefs [sc/run (constantly (md/success-deferred {:exit ::test-exit}))]
    (testing "runs sidecar poll loop, returns exit code"
      (is (= ::test-exit (sut/sidecar {:config {:dev-mode true}}))))

    (testing "posts start and end events"
      (let [{:keys [recv] :as e} (h/fake-events)]
        (is (some? (sut/sidecar {:events e
                                 :config {:dev-mode true}})))
        (is (= [:sidecar/start :sidecar/end]
               (map :type @recv)))))

    (testing "events contain job details from config"
      (let [{:keys [recv] :as e} (h/fake-events)
            job {:id "test-job"}]
        (is (some? (sut/sidecar {:events e
                                 :config {:sidecar {:job-config {:job job}}
                                          :dev-mode true}})))
        (is (= job (-> @recv first :job)))))))
