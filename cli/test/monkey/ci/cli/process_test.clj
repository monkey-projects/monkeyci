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

(deftest test!
  (let [dir "test/dir"]
    (with-redefs [bp/process identity]
      (testing "runs child process to execute unit tests"
        (is (= "clojure" (-> (sut/test! dir {})
                             :cmd
                             first))))

      (testing "enables watch mode if passed in opts"
        (is (cs/includes? (-> (sut/test! dir {:watch? true})
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
    (is (= (-> (fs/cwd) (fs/parent) (fs/path "test-lib") str)
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
