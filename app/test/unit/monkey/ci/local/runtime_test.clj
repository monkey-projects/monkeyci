(ns monkey.ci.local.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as a]
             [protocols :as p]]
            [monkey.ci.local.runtime :as sut]))

(deftest start-and-post
  (let [r (sut/start-and-post {} {:type :test})]
    (testing "returns deferred"
      (is (md/deferred? r)))))

(deftest make-system
  (let [sys (sut/make-system {})]
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
      (testing "has workspace"))))
