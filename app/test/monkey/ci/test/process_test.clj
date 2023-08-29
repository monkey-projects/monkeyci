(ns monkey.ci.test.process-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci.process :as sut]
            [monkey.ci.utils :as u]))

(def cwd (u/cwd))

(defn example [subdir]
  (.getAbsolutePath (io/file cwd "examples" subdir)))

(deftest ^:slow execute!
  (testing "executes build script in separate process"
    (is (zero? (:exit (sut/execute! {:dev-mode true
                                     :script-dir (example "basic-clj")})))))

  (testing "fails when script fails"
    (is (pos? (:exit (sut/execute! {:dev-mode true
                                    :script-dir (example "failing")})))))

  (testing "fails when script not found"
    (is (thrown? java.io.IOException (sut/execute! {:dev-mode true
                                                    :script-dir (example "non-existing")})))))
