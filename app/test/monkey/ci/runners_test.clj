(ns monkey.ci.runners-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [process :as p]
             [runners :as sut]
             [utils :as u]]
            [monkey.ci.helpers :as h]))

(deftest build-local
  (with-redefs [p/execute! (constantly (md/success-deferred {:exit 0}))]

    (testing "returns a deferred that waits for build to complete"
      (is (md/deferred? (sut/build-local {:build {:script-dir "examples/basic-clj"}}))))
    
    (testing "when script not found"
      (testing "returns exit code 1"
        (is (= 1 (-> {:build {:script-dir "nonexisting"}}
                     (sut/build-local)
                     (deref)))))

      (testing "fires `:build/completed` event with error result"
        (let [events (atom [])]
          (is (some? (-> {:build {:script-dir "nonexisting"}
                          :events {:poster (partial swap! events conj)}}
                         (sut/build-local)
                         (deref))))
          (is (not-empty @events))
          (is (= :error (:result (first @events)))))))

    (testing "deletes checkout dir"
      (letfn [(verify-checkout-dir-deleted [checkout-dir script-dir]
                (is (some? (-> {:build {:script-dir (u/abs-path script-dir)
                                        :checkout-dir (u/abs-path checkout-dir)}}
                               (sut/build-local)
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
              (verify-checkout-dir-deleted checkout-dir script-dir))))))))

(deftest download-src
  (testing "no-op if the source is local"
    (let [rt {}]
      (is (= rt (sut/download-src rt)))))

  (testing "gets src using git fn"
    (is (= "test/dir" (-> {:build
                           {:git {:url "http://git.test"}}
                           :git {:clone (constantly "test/dir")}}
                          (sut/download-src)
                          :build
                          :checkout-dir))))

  (testing "passes git config to git fn"
    (let [git-config {:url "http://test"
                      :branch "main"
                      :id "test-id"}]
      (is (= "ok" (-> {:build
                       {:git git-config}
                       :git {:clone (fn [c]
                                      (if (= (select-keys c (keys git-config)) git-config) "ok" "failed"))}}
                      (sut/download-src)
                      :build
                      :checkout-dir)))))

  (testing "calculates checkout dir if not specified"
    (is (cs/ends-with? (-> {:config {:checkout-base-dir "test-work-dir"}
                            :git {:clone :dir}
                            :build {:git {:url "http:/test.git"}
                                    :build-id "test-build"}}
                           (sut/download-src)
                           :build
                           :checkout-dir)
                       "test-work-dir/test-build")))

  (testing "uses dir from build config if specified"
    (is (= "git-dir"
           (-> {:args {:workdir "test-work-dir"}
                :git {:clone :dir}
                :build {:git {:url "http:/test.git"
                              :dir "git-dir"}}}
               (sut/download-src)
               :build
               :checkout-dir))))

  (testing "calculates script dir"
    (is (re-matches #".*test/dir/test-script$"
                    (-> {:build
                         {:git {:url "http://git.test"}
                          :script-dir "test-script"}
                         :git {:clone (constantly "test/dir")}}
                        (sut/download-src)
                        :build
                        :script-dir))))

  (testing "uses default script dir when none specified"
    (is (re-matches #".*test/dir/.monkeyci$"
                    (-> {:build
                         {:git {:url "http://git.test"}
                          :build-id "test-build"}
                         :git {:clone (constantly "test/dir")}
                         :checkout-base-dir "checkout"}
                        (sut/download-src)
                        :build
                        :script-dir)))))

(deftest store-src
  (testing "does nothing if no workspace configured"
    (let [rt {}]
      (is (= rt (sut/store-src rt)))))

  (testing "stores src dir using blob and build id with extension"
    (let [stored (atom {})
          rt {:workspace (h/->FakeBlobStore stored)
              :build {:checkout-dir "test-checkout"
                      :sid ["test-cust" "test-repo" "test-build"]}}]
      (is (some? (sut/store-src rt)))
      (is (= {"test-checkout" "test-cust/test-repo/test-build.tgz"} @stored))))

  (testing "returns updated context"
    (let [rt {:workspace (h/->FakeBlobStore (atom {}))
              :build {:checkout-dir "test-checkout"
                      :sid ["test-build"]}}]
      (is (= (assoc-in rt [:build :workspace] "test-build.tgz")
             (sut/store-src rt))))))

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

