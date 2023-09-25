(ns monkey.ci.test.runners-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci
             [events :as e]
             [process :as p]
             [runners :as sut]
             [spec :as spec]]
            [monkey.ci.test.helpers :as h]))

(defn- build-and-wait [ctx]
  (-> (sut/build-local ctx)
      (h/try-take 1000 :timeout)))

(deftest build-local
  (h/with-bus
    (fn [bus]
      (with-redefs [p/execute! (fn [v]
                                 (e/post-event bus
                                               {:type :command/completed
                                                :exit v}))]

        (testing "returns a channel that waits for build to complete"
          (is (spec/channel? (sut/build-local {:event-bus bus
                                               :args {:dir "examples/basic-clj"}}))))
        
        (testing "when script not found, returns exit code 1"
          (is (= 1 (sut/build-local {:event-bus bus
                                     :args {:dir "nonexisting"}}))))

        (testing "launches local build process with absolute script and work dirs"
          (let [r (build-and-wait {:event-bus bus
                                   :args {:dir "examples/basic-clj"}})]
            (is (true? (some-> r :build :script-dir (io/file) (.isAbsolute))))))

        (testing "passes pipeline to process"
          (is (= "test-pipeline" (-> {:event-bus bus
                                      :args {:dir "examples/basic-clj"
                                             :pipeline "test-pipeline"}}
                                     build-and-wait
                                     :build
                                     :pipeline))))

        (testing "with workdir"
          (testing "passes work dir to process"
            (h/with-tmp-dir base
              (is (true? (.mkdir (io/file base "local"))))
              (is (= base (-> {:event-bus bus
                               :args {:dir "local"
                                      :workdir base}}
                              (build-and-wait)
                              :build
                              :work-dir)))))
          
          (testing "combine relative script dir with workdir"
            (h/with-tmp-dir base
              (is (true? (.mkdir (io/file base "local"))))
              (is (= (str base "/local") (-> {:event-bus bus
                                              :args {:dir "local"
                                                     :workdir base}}
                                             (build-and-wait)
                                             :build
                                             :script-dir)))))

          (testing "leave absolute script dir as is"
            (h/with-tmp-dir base
              (is (= base (-> (build-and-wait {:event-bus bus
                                               :args {:dir base}})
                              :build
                              :script-dir))))))))))

(deftest build-completed
  (testing "returns `command/completed` event for each type"
    (->> [:success :warning :error nil]
         (map (fn [t]
                (is (= {:type :command/completed
                        :command :build}
                       (-> (sut/build-completed {:result t})
                           (select-keys [:type :command]))))))
         (doall)))

  (testing "adds exit code"
    (is (= 1 (-> (sut/build-completed {:exit 1})
                 :exit)))))

(deftest make-runner
  (testing "provides child type"
    (is (fn? (sut/make-runner {:runner {:type :child}}))))

  (testing "provides noop type"
    (let [r (sut/make-runner {:runner {:type :noop}})]
      (is (fn? r))
      (is (pos? (r {})))))

  (testing "provides default type"
    (let [r (sut/make-runner {})]
      (is (fn? r))
      (is (pos? (r {}))))))
