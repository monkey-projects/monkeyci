(ns monkey.ci.process-test
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [clojure
             [string :as cs]
             [test :refer :all]]
            [monkey.ci
             [process :as sut]
             [utils :as u]]
            [monkey.ci.test.runtime :as trt]))

(deftest test!
  (let [build {:build-id "test-build"}
        rt (trt/test-runtime)]
    
    (with-redefs [bp/process identity]
      (testing "runs child process to execute unit tests"
        (is (= "clojure" (-> (sut/test! build rt)
                             :cmd
                             first))))

      (testing "enables watch mode if passed in cmdline"
        (is (cs/includes? (-> (sut/test! build (assoc-in rt [:config :args :watch] true))
                              :cmd
                              (nth 2))
                          ":watch? true"))))))

(deftest generate-test-deps
  (testing "includes monkeyci test lib"
    (is (contains? (-> (sut/generate-test-deps false false)
                       :aliases
                       :monkeyci/test
                       :extra-deps)
                   'com.monkeyci/test)))

  (testing "points to local test lib dir in dev mode"
    (is (= (-> (u/cwd) (fs/parent) (fs/path "test-lib") str)
           (-> (sut/generate-test-deps true
                                       false)
               :aliases
               :monkeyci/test
               :extra-deps
               (get 'com.monkeyci/test)
               :local/root))))

  (testing "enables watching if specified"
    (is (true? (-> (sut/generate-test-deps false true)
                   :aliases
                   :monkeyci/test
                   :exec-args
                   :watch?)))))

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
