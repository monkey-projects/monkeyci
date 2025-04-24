(ns monkey.ci.events.mailman.nats-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.mailman.nats :as sut]))

(deftest types-to-subjects
  (letfn [(verify-types [types]
            (let [f (sut/types-to-subjects "monkeyci.test")]
              (doseq [t types]
                (is (string? (f t))
                    (str "should map " t)))))]
    (testing "returns a subject for each event type"
      (testing "build types"
        (verify-types [:build/triggered
                       :build/pending
                       :build/queued
                       :build/initializing
                       :build/start
                       :build/end
                       :build/canceled
                       :build/updated]))

      (testing "script types"
        (verify-types [:script/initializing
                       :script/start
                       :script/end]))

      (testing "job types"
        (verify-types [:job/pending
                       :job/queued
                       :job/initializing
                       :job/start
                       :job/end
                       :job/skipped
                       :job/executed]))

      (testing "container types"
        (verify-types [:container/pending
                       :container/initializing
                       :container/job-queued])))))
