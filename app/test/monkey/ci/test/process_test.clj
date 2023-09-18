(ns monkey.ci.test.process-test
  (:require [babashka.process :as bp]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci
             [process :as sut]
             [script :as script]]
            [monkey.ci.utils :as u]
            [monkey.ci.build.core :as bc]))

(def cwd (u/cwd))

(defn example [subdir]
  (.getAbsolutePath (io/file cwd "examples" subdir)))

(deftest run
  (testing "executes script with given args"
    (let [captured-args (atom nil)]
      (with-redefs [script/exec-script! (fn [args]
                                          (reset! captured-args args)
                                          bc/success)]
        (is (nil? (sut/run {:key :test-args})))
        (is (= {:key :test-args} (-> @captured-args
                                     (select-keys [:key]))))))))

(deftest ^:slow execute-slow!
  (testing "executes build script in separate process"
    (is (zero? (:exit (sut/execute! {:dev-mode true
                                     :script-dir (example "basic-clj")})))))

  (testing "fails when script fails"
    (is (pos? (:exit (sut/execute! {:dev-mode true
                                    :script-dir (example "failing")})))))

  (testing "fails when script not found"
    (is (thrown? java.io.IOException (sut/execute! {:dev-mode true
                                                    :script-dir (example "non-existing")})))))

(defn- find-arg
  "Finds the argument value for given key"
  [{:keys [args]} k]
  (->> args
       :cmd
       (drop-while (partial not= (str k)))
       (second)))

(deftest execute!
  (with-redefs [bp/process (fn [args]
                             (future {:args args
                                      :exit 1234}))]
    
    (testing "returns exit code"
      (is (= 1234 (:exit (sut/execute! {:dev-mode true
                                        :script-dir (example "failing")})))))

    (testing "invokes in script dir"
      (is (= "test-dir" (-> (sut/execute! {:script-dir "test-dir"})
                            :args
                            :dir))))

    (testing "passes work dir in edn"
      (is (= "\"work-dir\"" (-> {:work-dir "work-dir"}
                                (sut/execute!)
                                (find-arg :work-dir)))))

    (testing "passes script dir in edn"
      (is (= "\"script-dir\"" (-> {:script-dir "script-dir"}
                                  (sut/execute!)
                                  (find-arg :script-dir)))))

    (testing "passes pipeline in edn"
      (is (= "\"test-pipeline\"" (-> {:pipeline "test-pipeline"}
                                     (sut/execute!)
                                     (find-arg :pipeline)))))))
