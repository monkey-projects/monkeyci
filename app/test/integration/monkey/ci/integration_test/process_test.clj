(ns monkey.ci.integration-test.process-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [logging :as l]
             [process :as sut]
             [utils :as u]]))

(deftest ^:integration execute!
  (let [rt {:config {:dev-mode true}
            :logging {:maker (l/make-logger {:logging {:type :inherit}})}}]
    
    (testing "executes build script in separate process"
      (is (zero? (-> {:script {:script-dir (example "basic-clj")}
                      :build-id (u/new-build-id)}
                     (sut/execute! rt)
                     deref
                     :exit))))

    (testing "fails when script fails"
      (is (pos? (-> {:script {:script-dir (example "failing")}
                     :build-id (u/new-build-id)}
                    (sut/execute! rt)
                    deref
                    :exit))))

    (testing "fails when script not found"
      (is (thrown? java.io.IOException (sut/execute!
                                        {:script {:script-dir (example "non-existing")}}
                                        rt))))))

