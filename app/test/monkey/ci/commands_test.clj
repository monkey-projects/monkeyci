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

  (testing "adds `build` to runtime"
    (is (map? (-> {:config {:args {:git-url "test-url"
                                   :branch "test-branch"
                                   :commit-id "test-id"}}
                   :runner :build}
                  (sut/run-build)))))

  (testing "adds build sid to build config"
    (let [{:keys [sid build-id]} (-> {:config {:args {:sid "a/b/c"}}
                                      :runner :build}
                                     (sut/run-build))]
      (is (= build-id (last sid)))
      (is (= ["a" "b" "c"] (take 3 sid)))))

  (testing "constructs `sid` from account settings if not specified"
    (let [{:keys [sid build-id]} (-> {:runner :build
                                      :config {:account {:customer-id "a"
                                                         :repo-id "b"}}}
                                     (sut/run-build))]
      (is (= build-id (last sid)))
      (is (= ["a" "b"] (take 2 sid)))))

  (testing "posts `build/end` event on exception"
    (let [{:keys [recv] :as e} (h/fake-events)]
      (is (= 1 (-> {:runner (fn [_] (throw (ex-info "test error" {})))
                    :events e}
                   (sut/run-build))))
      (is (= 1 (count @recv)))      
      (is (= :build/end (:type (first @recv)))))))

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

#_(deftest result-accumulator
  (testing "returns a map of type handlers"
    (is (map? (:handlers (sut/result-accumulator {})))))

  (testing "updates pipeline and step states"
    (let [{:keys [handlers state]} (sut/result-accumulator {})
          start-step (:step/start handlers)
          end-step (:step/end handlers)]
      (is (some? (start-step {:name "step-1"
                              :index 0
                              :pipeline {:index 0
                                         :name "test-pipeline"}})))
      (is (some? (end-step {:name "step-1"
                            :index 0
                            :pipeline {:index 0
                                       :name "test-pipeline"}
                            :status :success})))
      (is (= :success (get-in @state [:pipelines "test-pipeline" :steps 0 :status])))))

  (testing "has build completed handler"
    (let [acc (sut/result-accumulator {})
          c (get-in acc [:handlers :build/completed])]
      (is (fn? c))
      (is (some? (reset! (:state acc) {:pipelines
                                       {"test-pipeline"
                                        {:steps
                                         {0
                                          {:name "test-step"
                                           :start-time 0
                                           :end-time 100
                                           :status :success}}}}})))
      (is (nil? (c {}))))))

(deftest prepare-build-ctx
  (testing "adds build object to runtime"
    (is (some? (-> {:config {:args {:dir "test-dir"}}}
                   (sut/prepare-build-ctx)
                   :build)))))

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
  (with-redefs [sc/run (constantly (md/success-deferred {:exit-code ::test-exit}))]
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
