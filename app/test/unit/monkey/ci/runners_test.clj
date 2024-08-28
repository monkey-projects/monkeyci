(ns monkey.ci.runners-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [process :as p]
             [runners :as sut]
             [script :as script]
             [utils :as u]]
            [monkey.ci.helpers :as h]))

(deftest build-local
  (with-redefs [p/execute! (constantly (md/success-deferred {:exit 0}))]

    (testing "returns a deferred that waits for build to complete"
      (is (md/deferred? (sut/build-local {:script
                                          {:script-dir "examples/basic-clj"}}
                                         {}))))
    
    (testing "when script not found"
      (testing "returns exit code 1"
        (is (= 1 (-> {:script {:script-dir "nonexisting"}}
                     (sut/build-local {})
                     (deref)))))

      (testing "fires `:build/end` event with error status"
        (let [{:keys [recv] :as e} (h/fake-events)]
          (is (some? (-> {:script {:script-dir "nonexisting"}}
                         (sut/build-local {:events e} )
                         (deref))))
          (is (not-empty @recv))
          (let [m (->> @recv
                       (filter (comp (partial = :build/end) :type))
                       (first))]
            (is (some? m))
            (is (= :error (get-in m [:build :status])))))))

    (testing "deletes checkout dir"
      (letfn [(verify-checkout-dir-deleted [checkout-dir script-dir]
                (is (some? (-> {:script {:script-dir (u/abs-path script-dir)}
                                :checkout-dir (u/abs-path checkout-dir)
                                :cleanup? true
                                :git {:dir "test"}}
                               (sut/build-local {})
                               (deref))))
                (is (false? (.exists checkout-dir))))]
        
        (testing "when script exists"
          (h/with-tmp-dir dir
            (let [checkout-dir (io/file dir "checkout")
                  script-dir (doto (io/file checkout-dir "script")
                               (.mkdirs))]
              (spit (io/file script-dir "build.clj") "[]")
              (verify-checkout-dir-deleted checkout-dir script-dir))))

        (testing "when script was not found"
          (h/with-tmp-dir dir
            (let [checkout-dir (doto (io/file dir "checkout")
                                 (.mkdirs))
                  script-dir (io/file checkout-dir "nonexisting")]
              (verify-checkout-dir-deleted checkout-dir script-dir))))))

    (testing "does not delete checkout dir when no cleanup flag set"
      (h/with-tmp-dir dir
        (let [checkout-dir (io/file dir "checkout")
              script-dir (doto (io/file checkout-dir "script")
                           (.mkdirs))]
          (spit (io/file script-dir "build.clj") "[]")
          (is (some? (-> {:checkout-dir (u/abs-path checkout-dir)}
                         (sut/build-local {})
                         (deref))))
          (is (true? (.exists checkout-dir))))))

    (testing "fires `build/start` event with sid"
      (let [{:keys [recv] :as e} (h/fake-events)
            rt {:events e}
            build {:build-id "test-build"
                   :sid ["test" "build"]}]
        (is (some? @(sut/build-local build rt)))
        (is (not-empty @recv))
        (let [evt (first @recv)]
          (is (= :build/start (:type evt)))
          (is (= (:sid build) (:sid evt))))))))

(deftest download-src
  (testing "no-op if the source is local"
    (let [build {}]
      (is (= build (sut/download-src build {})))))

  (testing "gets src using git fn"
    (is (= "test/dir" (-> {:git {:url "http://git.test"}}
                          (sut/download-src {:git {:clone (constantly "test/dir")}})
                          :checkout-dir))))

  (testing "passes git config to git fn"
    (let [git-config {:url "http://test"
                      :branch "main"
                      :id "test-id"}]
      (is (= "ok" (-> {:git git-config}
                      (sut/download-src
                       {:git {:clone (fn [c]
                                       (if (= (select-keys c (keys git-config)) git-config)
                                         "ok"
                                         (str "failed: " (pr-str c))))}})
                      :checkout-dir)))))

  (testing "calculates checkout dir if not specified"
    (is (cs/ends-with? (-> {:git {:url "http:/test.git"}
                                    :build-id "test-build"}
                           (sut/download-src
                            {:config {:checkout-base-dir "test-work-dir"}
                             :git {:clone :dir}})
                           :checkout-dir)
                       "test-work-dir/test-build")))

  (testing "uses dir from build config if specified"
    (is (= "git-dir"
           (-> {:git {:url "http:/test.git"
                      :dir "git-dir"}}
               (sut/download-src {:args {:workdir "test-work-dir"}
                                  :git {:clone :dir}})
               :checkout-dir))))

  (testing "calculates script dir"
    (is (re-matches #".*test/dir/test-script$"
                    (-> {:git {:url "http://git.test"}
                         :script {:script-dir "test-script"}}
                        (sut/download-src {:git {:clone (constantly "test/dir")}})
                        :script
                        :script-dir))))

  (testing "uses default script dir when none specified"
    (is (re-matches #".*test/dir/.monkeyci$"
                    (-> {:git {:url "http://git.test"}
                         :build-id "test-build"}
                        (sut/download-src
                         {:git {:clone (constantly "test/dir")}
                          :config {:checkout-base-dir "checkout"}})
                        :script
                        :script-dir)))))

(deftest store-src
  (testing "does nothing if no workspace configured"
    (let [build {}]
      (is (= build (sut/store-src build {})))))

  (testing "stores src dir using blob and build id with extension"
    (let [stored (atom {})
          rt {:workspace (h/fake-blob-store stored)}
          build {:checkout-dir "test-checkout"
                 :sid ["test-cust" "test-repo" "test-build"]}]
      (is (some? (sut/store-src build rt)))
      (is (= {"test-cust/test-repo/test-build.tgz" "test-checkout"} @stored))))

  (testing "returns updated build"
    (let [rt {:workspace (h/fake-blob-store (atom {}))}
          build {:checkout-dir "test-checkout"
                 :sid ["test-build"]}]
      (is (= (assoc build :workspace "test-build.tgz")
             (sut/store-src build rt))))))

(deftest make-runner
  (let [build {:build-id "test-build"}]
    (testing "provides child type"
      (is (fn? (sut/make-runner {:runner {:type :child}}))))

    (testing "provides noop type"
      (let [r (sut/make-runner {:runner {:type :noop}})]
        (is (fn? r))
        (is (pos? (r build {})))))

    (testing "provides default type"
      (let [r (sut/make-runner {})]
        (is (fn? r))
        (is (pos? (r build {})))))

    (testing "provides in-process type"
      (with-redefs [script/exec-script! (constantly {:status :success})]
        (let [r (sut/make-runner {:runner {:type :in-process}})]
          (is (fn? r))
          (is (= 0 (r build {}))))))))

