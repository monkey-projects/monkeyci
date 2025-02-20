(ns monkey.ci.local.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as a]
             [protocols :as p]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.local
             [config :as lc]
             [runtime :as sut]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.mailman :as tm]))

(deftest start-and-post
  (h/with-tmp-dir dir
    (let [broker (tm/test-component)
          evt {:type :test}
          r (-> {:mailman broker}
                (lc/set-work-dir dir)
                (sut/start-and-post evt))]
      (testing "returns deferred"
        (is (md/deferred? r)))

      (testing "posts event to broker"
        (is (= [evt] (-> broker
                         :broker
                         (tm/get-posted))))))))

(deftest make-system
  (h/with-tmp-dir dir
    (let [sys (-> {}
                  (lc/set-work-dir dir)
                  (sut/make-system))]
      (testing "has mailman"
        (is (some? (:mailman sys))))

      (testing "has artifacts"
        (is (a/repo? (:artifacts sys))))

      (testing "has cache"
        (is (a/repo? (:cache sys))))

      (testing "has build params"
        (is (satisfies? p/BuildParams (:params sys))))

      (testing "has containers")

      (testing "when container build"
        (testing "has workspace")))))
