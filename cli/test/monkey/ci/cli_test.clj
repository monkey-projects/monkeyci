(ns monkey.ci.cli-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.walk :as w]
            [cli-matic.core :as cc]
            [monkey.ci
             [cli :as sut]
             [commands :as cmd]]))

(defn- set-invoker
  "Updates the cli config to replace the `runs` config with the given invoker."
  [conf inv]
  (w/prewalk
   (fn [x]
     (if (and (map-entry? x) (= :runs (first x)))
       [:runs (inv (second x))]
       x))
   conf))

(defn- run-cli
  "Test helper that intercepts the `:runs` in the cli config for testing purposes. 
   Returns a structure that contains the invoked handler, exit code and arguments."
  [& args]
  (let [cmd (atom nil)
        inv (fn [h]
              (fn [args]
                (reset! cmd {:cmd h
                             :args args})))]
    (with-redefs [cli-matic.platform/exit-script (constantly :exit)]
      (let [r (cc/run-cmd args (set-invoker sut/cli-config inv))]
        (assoc @cmd :exit r)))))

(deftest cli
  (testing "has config"
    (is (some? sut/cli-config)))

  (testing "`build`"
    (testing "`run`"
      (testing "runs build"
        (let [r (run-cli "build" "run")]
          (is (= :exit (:exit r)))
          (is (= cmd/run-build-local (:cmd r)))))

      (testing "accepts script dir `-d`"
        (let [lc (run-cli "build" "run" "-d" "test-dir")]
          (is (= "test-dir" (get-in lc [:args :dir])))))

              (testing "uses default script dir when not provided"
                (let [lc (run-cli "build" "run")]
                  (is (= ".monkeyci" (get-in lc [:args :dir])))))

              (testing "accepts global working dir `-w`"
                (let [lc (run-cli "-w" "work-dir" "build" "run")]
                  (is (= "work-dir" (get-in lc [:args :workdir])))))
              
              (testing "accepts dev mode"
                (let [lc (run-cli "--dev-mode" "build" "run")]
                  (is (true? (get-in lc [:args :dev-mode])))))

              (testing "accepts git url"
                (let [lc (run-cli "build" "run" "--git-url" "http://test-url")]
                  (is (= "http://test-url" (get-in lc [:args :git-url])))))

              (testing "accepts git branch"
                (let [lc (run-cli "build" "run" "--branch" "test-branch")]
                  (is (= "test-branch" (get-in lc [:args :branch])))))

              (testing "accepts git tag"
                (let [lc (run-cli "build" "run" "--tag" "test-tag")]
                  (is (= "test-tag" (get-in lc [:args :tag])))))

              (testing "accepts commit id"
                (let [lc (run-cli "build" "run" "--commit-id" "test-id")]
                  (is (= "test-id" (get-in lc [:args :commit-id])))))

              (testing "accepts config file"
                (is (= ["test-file"] (-> (run-cli "-c" "test-file" "build" "run")
                                         (get-in [:args :config-file]))))
                (is (= ["test-file"] (-> (run-cli "--config-file" "test-file" "build" "run")
                                         (get-in [:args :config-file])))))
              
              (testing "accepts multiple config files"
                (let [lc (run-cli "-c" "first.edn" "-c" "second.edn" "build" "run")]
                  (is (= ["first.edn" "second.edn"]
                         (get-in lc [:args :config-file])))))
              
              (testing "accepts build sid"
                (is (= "test-sid" (-> (run-cli "build" "run" "--sid" "test-sid")
                                      (get-in [:args :sid])))))

              (testing "accepts multiple build params"
                (is (= ["key1=value1" "key2=value2"]
                       (-> (run-cli "build" "run" "-p" "key1=value1" "--param" "key2=value2")
                           (get-in [:args :param])))))

              (testing "accepts build param files"
                (is (= ["params.yml" "params.edn"]
                       (-> (run-cli "build" "run"
                                    "--param-file" "params.yml"
                                    "--param-file" "params.edn")
                           (get-in [:args :param-file])))))

              (testing "accepts api url"
                (is (= "http://test"
                       (-> (run-cli "build"
                                    "--api" "http://test"
                                    "run")
                           (get-in [:args :api])))))

              (testing "accepts api key"
                (is (= "test-key"
                       (-> (run-cli "build"
                                    "--api-key" "test-key"
                                    "run")
                           (get-in [:args :api-key])))))

              (testing "accepts job filter"
                (is (= ["test-job"]
                       (-> (run-cli "build"
                                    "run"
                                    "--filter" "test-job")
                           (get-in [:args :filter]))))))))
