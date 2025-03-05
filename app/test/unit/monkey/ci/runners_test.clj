(ns monkey.ci.runners-test
  (:require [clojure
             [string :as cs]
             [test :refer :all]]
            [monkey.ci.runners :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest download-src
  (testing "no-op if the source is local"
    (let [build {}]
      (is (= build (sut/download-src build {})))))

  (testing "gets src using git fn"
    (is (= "test/dir" (-> {:git {:url "http://git.test"}
                           :build-id "test-build"}
                          (sut/download-src {:git {:clone (constantly "test/dir")}
                                             :config {:checkout-base-dir "/tmp"}})
                          :checkout-dir))))

  (testing "passes git config to git fn"
    (let [git-config {:url "http://test"
                      :branch "main"
                      :id "test-id"}]
      (is (= "ok" (-> {:git git-config
                       :build-id "test-build"}
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
                         :script {:script-dir "test-script"}
                         :build-id "test-build"}
                        (sut/download-src {:git {:clone (constantly "test/dir")}
                                           :config {:checkout-base-dir "/tmp"}})
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


