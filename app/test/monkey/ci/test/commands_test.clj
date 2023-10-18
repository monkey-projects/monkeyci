(ns monkey.ci.test.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [commands :as sut]
             [events :as e]
             [spec :as spec]]
            [monkey.ci.test.helpers :as h]))

(deftest build
  (testing "invokes runner from context"
    (let [ctx {:runner (constantly :invoked)}]
      (is (= :invoked (sut/build ctx)))))

  (testing "adds `build` to context"
    (is (map? (-> {:args {:git-url "test-url"
                          :branch "test-branch"
                          :commit-id "test-id"}
                   :runner :build}
                  (sut/build)))))

  (testing "accumulates build results from events"
    (let [registered (atom [])]
      (with-redefs [e/register-handler (fn [_ t _]
                                         (swap! registered conj t))]
        (h/with-bus
          (fn [bus]
            (is (some? (-> {:event-bus bus
                            :runner (constantly :ok)}
                           (sut/build))))
            (is (not-empty @registered))))))))

(deftest result-accumulator
  (testing "returns a map of type handlers"
    (is (map? (:handlers (sut/result-accumulator)))))

  (testing "updates pipeline and step states"
    (let [{:keys [handlers state]} (sut/result-accumulator)
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
    (let [acc (sut/result-accumulator)
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
               :script-dir)))))

(deftest http-server
  (testing "returns a channel"
    (is (spec/channel? (sut/http-server {})))))
