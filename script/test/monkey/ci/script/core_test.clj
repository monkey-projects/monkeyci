(ns monkey.ci.script.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [monkey.ci.script
             [build :as b]
             [config :as c]
             [core :as sut]]))

(deftest setup-runner
  (let [build (-> {:build-id "test-build"}
                  (b/set-script-dir "/test/dir/script")
                  (b/set-checkout-dir "/test/dir"))
        conf (-> c/empty-config
                 (c/set-build build)
                 (c/set-api {:url "http://localhost:1234"
                             :token "test-token"}))
        r (sut/setup-runner conf)]
    (testing "registers listener with router"
      (is (some? (:router r)))
      (is (some? (:listener r))))))

(deftest run-script
  (fs/with-temp-dir [dir]
    (let [src "dev-resources/test"
          sd (fs/path dir "script")
          build (-> {:build-id "test-build"}
                    (b/set-script-dir sd)
                    (b/set-checkout-dir dir))
          conf (-> c/empty-config
                   (c/set-build build)
                   (c/set-api {:url "http://localhost:1234"
                               :token "test-token"}))]
      (is (some? (fs/copy-tree src sd)))

      (let [r (sut/run-script conf)]
        (is (some? r) "returns channel")
        (let [res (first (ca/alts!! [r (ca/timeout 1000)]))]
          (is (some? res))
          (is (= build (select-keys (:build res) (keys build)))
              "result contains input build")
          (is (= 1 (count (:jobs res)))
              "result contains executed jobs")
          (is (= :success (-> res :jobs first :status))))))))

(deftest run-cli
  (testing "invokes `run-script` with config from args"
    (with-redefs [sut/run-script identity]
      (let [r (sut/run-cli {:url "http://test-url" :sid "a/b/c"})]
        (is (= "http://test-url" (-> r
                                     (c/api)
                                     :url)))))))

(deftest cli->conf
  (let [args {:url "http://test-url"
              :token "test-token"
              :sid "test-org/test-repo/test-build"
              :checkout-dir "/test/dir"}
        conf (sut/cli->conf args)]
    (testing "sets api config"
      (is (= {:url "http://test-url"
              :token "test-token"}
             (c/api conf))))

    (testing "sets build"
      (is (= {:org-id "test-org"
              :repo-id "test-repo"
              :build-id "test-build"
              :checkout-dir "/test/dir"}
             (c/build conf))))))
