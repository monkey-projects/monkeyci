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
                                               {:type :build/completed
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

(deftest download-src
  (testing "no-op if the source is local"
    (let [ctx {}]
      (is (= ctx (sut/download-src ctx)))))

  (testing "gets src using git fn"
    (is (= "test/dir" (-> {:build
                           {:git {:url "http://git.test"}}
                           :git {:fn (constantly "test/dir")}}
                          (sut/download-src)
                          :build
                          :work-dir))))

  (testing "passes git config to git fn"
    (let [git-config {:url "http://test"
                      :branch "main"
                      :id "test-id"}]
      (is (= "ok" (-> {:build
                       {:git git-config}
                       :git {:fn (fn [c]
                                   (if (= (select-keys c (keys git-config)) git-config) "ok" "failed"))}}
                      (sut/download-src)
                      :build
                      :work-dir)))))

  (testing "passes work dir as git checkout dir"
    (is (= "test-work-dir" (-> {:args {:workdir "test-work-dir"}
                                :git {:fn :work-dir}
                                :build {:git {:url "http:/test.git"}}}
                               (sut/download-src)
                               :build
                               :work-dir))))

  (testing "calculates script dir"
    (is (re-matches #".*test/dir/test-script$"
                    (-> {:build
                         {:git {:url "http://git.test"}}
                         :git {:fn (constantly "test/dir")}
                         :args {:dir "test-script"}}
                        (sut/download-src)
                        :build
                        :script-dir)))))

(deftest build-completed
  (testing "returns exit code for each type"
    (->> [:success :warning :error nil]
         (map (fn [t]
                (is (number? (sut/build-completed {:result t
                                                   :exit 0})))))
         (doall))))

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
