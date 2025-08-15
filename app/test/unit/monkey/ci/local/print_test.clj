(ns monkey.ci.local.print-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.stream :as ms]
            [monkey.ci.local.print :as sut]
            [monkey.mailman.core :as mmc]))

(deftest routes
  (testing "handles build and job events"
    (let [exp [:build/initializing
               :build/start
               :build/end
               :script/start
               :script/end
               :job/start
               :job/end]
          r (->> (sut/make-routes {})
                 (map first)
                 (set))]
      (doseq [e exp]
        (is (r e) (str "should handle " e)))))

  (testing "event"
    (let [s (ms/stream)
          r (->> (sut/make-routes {:printer (sut/stream-printer s)})
                 (mmc/router))
          verify-evt (fn [evt]
                       (is (nil? (-> evt
                                     (r)
                                     first
                                     :result)))
                       (is (some? @(ms/try-take! s 100))))]
      (testing "`build/start` prints to output"
        (verify-evt {:type :build/start
                     :sid ["test-org" "test-repo" "test-build"]}))

      (testing "`build/end` prints to output"
        (verify-evt {:type :build/end
                     :sid ["test-org" "test-repo" "test-build"]
                     :status :success}))

      (testing "`script/start` prints to output"
        (verify-evt {:type :script/start
                     :sid ["test-org" "test-repo" "test-build"]})))))
