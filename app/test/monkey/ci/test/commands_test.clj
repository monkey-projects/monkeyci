(ns monkey.ci.test.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci
             [commands :as sut]
             [events :as e]
             [sidecar :as sc]
             [spec :as spec]]
            [monkey.ci.test.helpers :as h]
            [org.httpkit.fake :as f]))

(deftest run-build
  (testing "invokes runner from context"
    (let [ctx {:runner (constantly :invoked)}]
      (is (= :invoked (sut/run-build ctx)))))

  (testing "adds `build` to context"
    (is (map? (-> {:args {:git-url "test-url"
                          :branch "test-branch"
                          :commit-id "test-id"}
                   :runner :build}
                  (sut/run-build)))))

  (testing "adds build sid to build config"
    (let [{:keys [sid build-id]} (-> {:args {:sid "a/b/c"}
                                      :runner :build}
                                     (sut/run-build))]
      (is (= build-id (last sid)))
      (is (= ["a" "b" "c"] (take 3 sid)))))

  (testing "constructs `sid` from account settings if not specified"
    (let [{:keys [sid build-id]} (-> {:runner :build
                                      :account {:customer-id "a"
                                                :project-id "b"
                                                :repo-id "c"}}
                                     (sut/run-build))]
      (is (= build-id (last sid)))
      (is (= ["a" "b" "c"] (take 3 sid)))))

  (testing "accumulates build results from events"
    (let [registered (atom [])]
      (with-redefs [e/register-handler (fn [_ t _]
                                         (swap! registered conj t))]
        (h/with-bus
          (fn [bus]
            (is (some? (-> {:event-bus bus
                            :runner (constantly :ok)}
                           (sut/run-build))))
            (is (not-empty @registered))))))))

(deftest list-builds
  (testing "reports builds from server"
    (let [reported (atom [])
          builds {:key "value"}
          ctx {:reporter (partial swap! reported conj)
               :account {:url "http://server/api"
                         :customer-id "test-cust"
                         :project-id "test-project"
                         :repo-id "test-repo"}}]
      (f/with-fake-http ["http://server/api/customer/test-cust/project/test-project/repo/test-repo/builds"
                         (pr-str builds)]
        (is (some? (sut/list-builds ctx)))
        (is (pos? (count @reported)))
        (let [r (first @reported)]
          (is (= :build/list (:type r)))
          (is (= builds (:builds r))))))))

(deftest result-accumulator
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
  (testing "adds build id"
    (is (re-matches #"build-\d+"
                    (-> (sut/prepare-build-ctx {})
                        :build
                        :build-id))))

  (testing "defaults to `main` branch"
    (is (= "main"
           (-> {:args {:git-url "test-url"}}
               (sut/prepare-build-ctx)
               :build
               :git
               :branch))))

  (testing "takes global work dir as build checkout dir"
    (is (= "global-work-dir"
           (-> {:work-dir "global-work-dir"
                :args {:dir ".monkeci"}}
               (sut/prepare-build-ctx)
               :build
               :checkout-dir))))

  (testing "adds pipeline from args"
    (is (= "test-pipeline"
           (-> {:args {:pipeline "test-pipeline"}}
               (sut/prepare-build-ctx)
               :build
               :pipeline))))

  (testing "adds script dir from args, as relative to work dir"
    (is (= "work-dir/test-script"
           (-> {:args {:dir "test-script"}
                :work-dir "work-dir"}
               (sut/prepare-build-ctx)
               :build
               :script-dir))))

  (testing "with git opts"
    (testing "sets git opts in build config"
      (is (= {:url "test-url"
              :branch "test-branch"
              :id "test-id"}
             (-> {:args {:git-url "test-url"
                         :branch "test-branch"
                         :commit-id "test-id"}}
                 (sut/prepare-build-ctx)
                 :build
                 :git))))

    (testing "sets script dir to arg"
      (is (= "test-script"
             (-> {:args {:git-url "test-url"
                         :branch "test-branch"
                         :commit-id "test-id"
                         :dir "test-script"}
                  :work-dir "work"}
                 (sut/prepare-build-ctx)
                 :build
                 :script-dir)))))

  (testing "when sid specified"
    (testing "parses on delimiter"
      (is (= ["a" "b" "c"]
             (->> {:args {:sid "a/b/c"}}
                  (sut/prepare-build-ctx)
                  :build
                  :sid
                  (take 3)))))
    
    (testing "adds build id"
      (is (string? (-> {:args {:sid "a/b/c"}}
                       (sut/prepare-build-ctx)
                       :build
                       :sid
                       last))))

    (testing "when sid includes build id, reuses it"
      (let [sid "a/b/c/d"
            ctx (-> {:args {:sid sid}}
                    (sut/prepare-build-ctx)
                    :build)]
        (is (= "d" (:build-id ctx)))
        (is (= "d" (last (:sid ctx)))))))

  (testing "when no sid specified"
    (testing "leaves it unspecified"
      (is (empty? (-> {:args {}}
                      (sut/prepare-build-ctx)
                      :build
                      :sid))))))

(deftest http-server
  (testing "returns a channel"
    (is (spec/channel? (sut/http-server {})))))

(deftest watch
  (testing "sends request and returns channel"
    (with-redefs [http/get (constantly (md/success-deferred nil))]
      (is (spec/channel? (sut/watch {})))))

  (testing "reports received events from reader"
    (let [events (prn-str {:type :script/started
                           :message "Test event"})
          reported (atom [])]
      (with-redefs [http/get (constantly (md/success-deferred {:body (bs/to-reader events)}))]
        (is (spec/channel? (sut/watch {:reporter (partial swap! reported conj)})))
        (is (not-empty @reported))))))

(deftest sidecar
  (testing "polls for events"
    (with-redefs [sc/restore-src (constantly ::restored)
                  sc/poll-events (constantly ::polling)]
      (is (= ::polling (sut/sidecar {})))))

  (testing "restores src from workspace"
    (with-redefs [sc/restore-src (constantly ::restored)
                  sc/poll-events (fn [ctx]
                                   (when (= ::restored ctx)
                                     ::polling))]
      (is (= ::polling (sut/sidecar {}))))))
