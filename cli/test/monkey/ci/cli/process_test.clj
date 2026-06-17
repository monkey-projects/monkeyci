(ns monkey.ci.cli.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as ca]
            [clojure.string :as cs]
            [babashka
             [fs :as fs]
             [process :as bp]]
            [monkey.ci.cli.process :as sut]))

(deftest run-test
  (testing "returns the exit code of the process"
    (is (zero? (sut/run ["echo" "hello"] "."))))

  (testing "returns non-zero exit code on failure"
    (is (not (zero? (sut/run ["sh" "-c" "exit 1"] ".")))))

  (testing "runs the process in the specified directory"
    (let [tmp (System/getProperty "java.io.tmpdir")]
      (is (zero? (sut/run ["sh" "-c" "test -d ."] tmp))))))

(deftest add-logback-config
  (testing "adds jvm opts to alias"
    (is (= ["-Dlogback.configurationFile=/test/path"]
           (-> {:aliases
                {::test
                 {:main-opts []}}}
               (sut/add-logback-config ::test
                                       "/test/path")
               (get-in [:aliases ::test :jvm-opts])))))

  (testing "adds jvm opts to existing"
    (is (= ["-Xmx1g"
            "-Dlogback.configurationFile=/test/path"]
           (-> {:aliases
                {::test
                 {:main-opts []
                  :jvm-opts ["-Xmx1g"]}}}
               (sut/add-logback-config ::test
                                       "/test/path")
               (get-in [:aliases ::test :jvm-opts]))))))

(deftest exit-fn
  (let [dest (ca/chan)
        inv (ca/timeout 1000)
        _ (ca/pipe dest inv)
        e (sut/exit-fn (fn [v]
                         (ca/>!! inv v)))]
    (testing "returns a fn"
      (is (fn? e)))

    (testing "invokes target fn"
      (is (some? (e ::test)))
      (is (= ::test (ca/<!! inv))))))

(deftest generate-deps
  (testing "generates basic deps.edn contents with alias"
    (is (contains? (-> (sut/generate-deps nil)
                       :aliases)
                   :monkeyci/build))))
