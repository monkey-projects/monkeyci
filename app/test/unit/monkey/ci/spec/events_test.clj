(ns monkey.ci.spec.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.events :as sut]
            [monkey.ci
             [cuid :as cuid]
             [time :as t]]))

(defn build-sid []
  (repeatedly 3 cuid/random-cuid))

(deftest event-spec
  (testing "validates `:build/initializing` event"
    (is (not (s/valid? ::sut/event
                       {:type :build/initializing
                        :time (t/now)
                        :sid (build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :build/initializing
                   :time (t/now)
                   :sid (build-sid)
                   :build {:build-id "test-build"}})))

  (testing "validates `:build/start` event"
    (is (not (s/valid? ::sut/event
                       {:type :build/start
                        :time (t/now)
                        :sid (build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :build/start
                   :time (t/now)
                   :sid (build-sid)
                   :credit-multiplier 2})))

  (testing "validates `:build/end` event"
    (is (not (s/valid? ::sut/event
                       {:type :build/end
                        :time (t/now)
                        :sid (build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :build/end
                   :time (t/now)
                   :sid (build-sid)
                   :status :success}))))
