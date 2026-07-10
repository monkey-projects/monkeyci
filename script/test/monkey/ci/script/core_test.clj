(ns monkey.ci.script.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [monkey.ci.script
             [api-client :as ac]
             [build :as b]
             [config :as c]
             [core :as sut]]))

(deftest setup-runner
  (with-redefs [ac/get-events (constantly (ca/to-chan! []))]
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
        (is (some? (:listener r)))))))

(deftest artifact-saver
  (testing "invokes api client"
    (let [c (fn [req]
              {:body (pr-str (select-keys req [:path]))})
          ctx {:job {:id "test-job"}}
          r (sut/artifact-saver c)]
      (is (= "/artifact/test-job/test-art"
             (r ctx {:id "test-art" :path "test-path"}))))))

(deftest artifact-restorer
  (testing "invokes api client"
    (let [c (fn [req]
              {:body (pr-str (select-keys req [:path]))})
          ctx {:job {:id "test-job"}}
          r (sut/artifact-restorer c)]
      (is (= "/artifact/test-job/test-art"
             (r ctx {:id "test-art" :path "test-path"}))))))

(deftest cache-saver
  (testing "invokes api client"
    (let [c (fn [req]
              {:body (pr-str (select-keys req [:path]))})
          ctx {:job {:id "test-job"}}
          r (sut/cache-saver c)]
      (is (= "/cache/test-job/test-art"
             (r ctx {:id "test-art" :path "test-path"}))))))

(deftest cache-restorer
  (testing "invokes api client"
    (let [c (fn [req]
              {:body (pr-str (select-keys req [:path]))})
          ctx {:job {:id "test-job"}}
          r (sut/cache-restorer c)]
      (is (= "/cache/test-job/test-art"
             (r ctx {:id "test-art" :path "test-path"}))))))

(deftest run-script
  (with-redefs [ac/get-events (constantly (ca/to-chan! []))
                ac/push-events (constantly true)]
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
            (is (= :success (-> res :jobs first :status)))))))))

(deftest run-bb-cli
  (testing "invokes `run-script` with config from args"
    (let [args (atom nil)]
      (with-redefs [sut/run-script (fn [arg]
                                     (reset! args arg)
                                     (ca/to-chan! [{:status :success}]))]
        (is (nil? (sut/run-bb-cli {:url "http://test-url" :sid "a/b/c"})))
        (is (= "http://test-url"
               (-> @args c/api :url)))))))

(deftest run-clj-cli
  (testing "invokes `run-script` with config from args"
    (let [args (atom nil)]
      (with-redefs [sut/run-script (fn [arg]
                                     (reset! args arg)
                                     (ca/to-chan! [{:status :success}]))]
        (is (nil? (sut/run-clj-cli {:config {::c/api {:url "http://test-url" :sid "a/b/c"}}})))
        (is (= "http://test-url"
               (-> @args c/api :url)))))))

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
