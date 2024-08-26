(ns monkey.ci.integration-test.process-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [monkey.ci
             [cuid :as cuid]
             [logging :as l]
             [process :as sut]
             [utils :as u]]
            [monkey.ci.helpers :as h]))

(defn example [subdir]
  (.getAbsolutePath (io/file (u/cwd) "examples" subdir)))

(deftest ^:integration execute!
  (let [rt {:config {:dev-mode true}
            :logging {:maker (l/make-logger {:logging {:type :inherit}})}
            :events (h/fake-events)
            :workspace (h/fake-blob-store)
            :artifacts (h/fake-blob-store)
            :cache (h/fake-blob-store)}]
    
    (testing "executes build script in separate process"
      (is (zero? (-> {:script {:script-dir (example "basic-clj")}
                      :build-id (cuid/random-cuid)}
                     (sut/execute! rt)
                     deref
                     :exit))))

    (testing "fails when script fails"
      (is (pos? (-> {:script {:script-dir (example "failing")}
                     :build-id (cuid/random-cuid)}
                    (sut/execute! rt)
                    deref
                    :exit))))

    (testing "fails when script not found"
      (is (thrown? java.io.IOException (sut/execute!
                                        {:script {:script-dir (example "non-existing")}}
                                        rt))))))

