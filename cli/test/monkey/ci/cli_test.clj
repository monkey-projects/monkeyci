(ns monkey.ci.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clj-kondo.core :as lint]
            [monkey.ci.cli
             [print :as p]
             [version :as v]]
            [monkey.ci.cli :as sut]))

(deftest print-version
  (let [printed (atom [])]
    (with-redefs [p/print-version (fn [v]
                                    (swap! printed conj v)
                                    nil)]
      (testing "prints version to stdout"
        (is (nil? (sut/print-version {})))
        (is (= 1 (count @printed)))
        (is (= v/version (first @printed)))))))

(deftest verify-test
  (testing "returns nil"
    (with-redefs [fs/path         (fn [& parts] (apply str parts))
                  fs/exists?      (constantly false)
                  lint/run!       (constantly {:summary {} :findings []})
                  p/print-summary (constantly nil)]
      (is (nil? (sut/verify {:dir "/some/dir"})))))

  (testing "lints the .monkeyci subdirectory when it exists"
    (let [linted-path (atom nil)]
      (with-redefs [fs/path         (fn [& parts] (apply str parts))
                    fs/exists?      (constantly true)
                    lint/run!       (fn [{[p] :lint}]
                                      (reset! linted-path p)
                                      {:summary {} :findings []})
                    p/print-summary (constantly nil)]
        (sut/verify {:dir "/project"})
        (is (= "/project.monkeyci" @linted-path)))))

  (testing "lints the given dir when .monkeyci subdirectory does not exist"
    (let [linted-path (atom nil)]
      (with-redefs [fs/path         (fn [& parts] (apply str parts))
                    fs/exists?      (constantly false)
                    lint/run!       (fn [{[p] :lint}]
                                      (reset! linted-path p)
                                      {:summary {} :findings []})
                    p/print-summary (constantly nil)]
        (sut/verify {:dir "/project"})
        (is (= "/project" @linted-path)))))

  (testing "delegates to print/print-findings when lint returns findings"
    (let [received (atom nil)]
      (with-redefs [fs/path          (fn [& parts] (apply str parts))
                    fs/exists?       (constantly false)
                    lint/run!        (constantly {:summary {}
                                                  :findings [{:filename "foo.clj"
                                                              :row      10
                                                              :message  "unused var"}]})
                    p/print-summary  (constantly nil)
                    p/print-findings (fn [f] (reset! received f))]
        (sut/verify {:dir "/project"})
        (is (= [{:filename "foo.clj" :row 10 :message "unused var"}]
               @received)))))

  (testing "does not call print/print-findings when there are no findings"
    (let [called (atom false)]
      (with-redefs [fs/path          (fn [& parts] (apply str parts))
                    fs/exists?       (constantly false)
                    lint/run!        (constantly {:summary {} :findings []})
                    p/print-summary  (constantly nil)
                    p/print-findings (fn [_] (reset! called true))]
        (sut/verify {:dir "/project"})
        (is (false? @called))))))
