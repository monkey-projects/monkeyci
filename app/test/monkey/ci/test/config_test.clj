(ns monkey.ci.test.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as cs]
            [monkey.ci
             [config :as sut]
             [context :as ctx]
             [spec :as spec]]
            [monkey.ci.test.helpers :as h]))

(deftest app-config
  (testing "provides default values"
    (is (= 3000 (-> (sut/app-config {} {})
                    :http
                    :port))))

  (testing "takes port from args"
    (is (= 1234 (-> (sut/app-config {} {:port 1234})
                    :http
                    :port))))

  (testing "takes github config from env"
    (is (= "test-secret" (-> {:monkeyci-github-secret "test-secret"}
                             (sut/app-config {})
                             :github
                             :secret))))

  (testing "sets runner type"
    (is (= :test-type (-> {:monkeyci-runner-type "test-type"}
                          (sut/app-config {})
                          :runner
                          :type))))

  (testing "sets `dev-mode` from args"
    (is (true? (->> {:dev-mode true}
                    (sut/app-config {})
                    :dev-mode))))

  (testing "matches app config spec"
    (is (true? (s/valid? ::spec/app-config (sut/app-config {} {})))))

  (testing "provides log-dir as absolute path"
    (is (re-matches #".+test-dir$" (-> {:monkeyci-log-dir "test-dir"}
                                       (sut/app-config {})
                                       (ctx/log-dir)))))

  (testing "loads config from `config-file` path"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test-config.edn")]
        (is (nil? (spit f (pr-str {:log-dir "some-log-dir"}))))
        (let [c (sut/app-config {} {:config-file (.getCanonicalPath f)})]
          (is (= "some-log-dir" (:log-dir c)))))))

  (testing "loads global config file"
    (h/with-tmp-dir dir
      (let [f (-> (io/file dir "test-config.edn")
                  (.getCanonicalPath))]
        (binding [sut/*global-config-file* f]
          (is (nil? (spit f (pr-str {:log-dir "some-log-dir"}))))
          (let [c (sut/app-config {} {})]
            (is (= "some-log-dir" (:log-dir c))))))))

  (testing "loads home config file"
    (h/with-tmp-dir dir
      (let [f (-> (io/file dir "home-config.edn")
                  (.getCanonicalPath))]
        (binding [sut/*home-config-file* f]
          (is (nil? (spit f (pr-str {:log-dir "some-log-dir"}))))
          (let [c (sut/app-config {} {})]
            (is (= "some-log-dir" (:log-dir c))))))))

  (testing "global `work-dir`"
    (testing "uses current as default"
      (is (some? (-> (sut/app-config {} {})
                     :work-dir))))

    (testing "takes work dir from env"
      (is (cs/ends-with? (-> (sut/app-config {:monkeyci-work-dir "test-dir"} {})
                             :work-dir)
                         "test-dir")))

    (testing "takes work dir from arg over env"
      (is (cs/ends-with? (-> (sut/app-config {:monkeyci-work-dir "test-dir"}
                                             {:workdir "arg-dir"})
                             :work-dir)
                         "arg-dir")))))

(deftest config->env
  (testing "empty for empty input"
    (is (empty? (sut/config->env {}))))

  (testing "prefixes config entries with `monkeyci-`"
    (is (= {:monkeyci-key "value"}
           (sut/config->env {:key "value"}))))

  (testing "flattens nested config maps"
    (is (= {:monkeyci-http-port 8080}
           (sut/config->env {:http {:port 8080}})))))

(deftest script-config
  (testing "sets containers type"
    (is (= :test-type (-> {:monkeyci-containers-type "test-type"}
                          (sut/script-config {})
                          :containers
                          :type))))

  (testing "matches spec"
    (is (true? (s/valid? ::spec/script-config (sut/script-config {} {}))))))

(deftest load-config-file
  (testing "`nil` if file does not exist"
    (is (nil? (sut/load-config-file "nonexisting.json"))))

  (testing "loads `.edn` files"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.edn")]
        (spit f (pr-str {:key "value"}))
        (is (= {:key "value"} (sut/load-config-file f))))))

  (testing "loads `.json` files"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.json")]
        (spit f "{\"key\":\"value\"}")
        (is (= {:key "value"} (sut/load-config-file f))))))

  (testing "converts to kebab-case for `.edn`"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.edn")]
        (spit f (pr-str {:testKey "value"}))
        (is (= {:test-key "value"} (sut/load-config-file f))))))

  (testing "converts to kebab-case for `.json`"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.json")]
        (spit f "{\"testKey\":\"value\"}")
        (is (= {:test-key "value"} (sut/load-config-file f)))))))
