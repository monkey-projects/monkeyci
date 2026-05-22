(ns monkey.ci.cli.test-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.cli.process :as process]
            [monkey.ci.cli.test :as sut]))

(deftest write-temp-bb-edn-test
  (let [dir  (fs/temp-dir)
        path (sut/write-temp-bb-edn dir "/tmp/kaocha-conf.edn")
        data (edn/read-string (slurp path))]

    (testing "returns path to an existing file"
      (is (fs/exists? path)))

    (testing "contains a test task"
      (is (contains? (:tasks data) 'test)))

    (testing "test task includes kaocha test runner as extra dep"
      (is (contains? (get-in data [:tasks 'test :extra-deps])
                     'lambdaisland/kaocha)))

    (testing "cognitect test runner dep specifies version"
      (let [dep (get-in data [:tasks 'test :extra-deps 'lambdaisland/kaocha])]
        (is (contains? dep :mvn/version))))

    (testing "test task invokes exec"
      (let [task (get-in data [:tasks 'test :task])]
        (is (= 'exec (first task)))))

    (testing "exec-args specify test dirs"
      (is (= "/tmp/kaocha-conf.edn" (get-in data [:tasks 'test :exec-args :config-file]))))))

(deftest write-kaocha-config
  (let [path (sut/write-kaocha-config {})]
    (testing "writes kaocha config file to temp"
      (is (fs/exists? path)))

    (let [data (edn/read-string (slurp path))]
      (is (map? data))

      (testing "contains test config"
        (is (not-empty (:kaocha/tests data)))))))

(deftest kaocha-config
  (let [c (sut/kaocha-config {})]
    (testing "contains test config"
      (is (= 1 (count (:kaocha/tests c))))
      (is (= :kaocha.type/clojure.test
             (-> c
                 :kaocha/tests
                 first
                 :kaocha.testable/type)))))

  (testing "configures watch mode"
    (is (true? (-> (sut/kaocha-config {:watch true})
                   :watch?)))))

(deftest run-tests-test
  (with-redefs [sut/write-temp-bb-edn (constantly "/tmp/test.edn")]
    (let [received (atom nil)]
      (with-redefs [process/run (fn [cmd dir] (reset! received {:cmd cmd :dir dir}) 0)]
        (sut/run-tests {:dir "/some/project"})
        
        (testing "passes --config with the generated bb.edn path to bb"
          (is (= "bb"           (first (:cmd @received))))
          (is (= "--config"     (second (:cmd @received))))
          (is (= "/tmp/test.edn" (nth (:cmd @received) 2))))

        (testing "runs test task"
          (is (= ["run" "test"] (drop 3 (:cmd @received)))))

        (testing "runs in the specified directory"
          (is (= "/some/project" (str (:dir @received)))))))

    (testing "returns the exit code from the process"
      (with-redefs [process/run (constantly 42)]
        (is (= 42 (sut/run-tests {:dir "."})))))

    (testing "uses absolute directory"
      (let [received (atom nil)]
        (with-redefs [process/run (fn [cmd dir] (reset! received {:cmd cmd :dir dir}) 0)]
          (sut/run-tests {:dir "test/dir"})
          (is (= (fs/absolutize "test/dir") (:dir @received))))))))
