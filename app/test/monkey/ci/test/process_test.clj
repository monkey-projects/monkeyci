(ns monkey.ci.test.process-test
  (:require [babashka.process :as bp]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci.process :as sut]
            [monkey.ci.utils :as u]))

(def cwd (u/cwd))

(defn example [subdir]
  (.getAbsolutePath (io/file cwd "examples" subdir)))

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
       (drop-while (partial not= (str k)))
       (second)))

(deftest execute!
  (with-redefs [bp/shell (fn [& args]
                           {:args args
                            :exit 1234})]
    
    (testing "returns exit code"
      (is (= 1234 (:exit (sut/execute! {:dev-mode true
                                        :script-dir (example "failing")})))))

    (testing "invokes in script dir"
      (is (= "test-dir" (-> (sut/execute! {:script-dir "test-dir"})
                            :args
                            (first)
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
