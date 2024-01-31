(ns monkey.ci.events.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.core :as sut]))

(deftest make-event
  (testing "adds timestamp to event"
    (is (number? (-> {:type :test-event}
                     (sut/make-event)
                     :timestamp)))))

(deftest sync-events
  (testing "can add listener"
    (let [s (sut/make-sync-events)]
      (is (= s (sut/add-listener s (constantly nil))))
      (is (= 1 (count @(.listeners s))))))

  (testing "can remove listener"
    (let [l (constantly nil)]
      (is (empty? (-> (sut/make-sync-events)
                      (sut/add-listener l)
                      (sut/remove-listener l)
                      (.listeners)
                      (deref))))))

  (testing "can dispatch single event"
    (let [recv (atom [])
          s (sut/make-sync-events)
          evt {:type :test-event}]
      (is (some? (sut/add-listener s (sut/no-dispatch
                                       (partial swap! recv conj)))))
      (is (= s (sut/post-events s evt)))
      (is (= [evt] @recv))))

  (testing "re-dispatches events"
    (let [recv (atom [])
          s (-> (sut/make-sync-events)
                (sut/add-listener (sut/filter-type :type/first (constantly {:type :type/second})))
                (sut/add-listener (sut/filter-type :type/second (fn [evt]
                                                                  (swap! recv conj evt)
                                                                  nil))))]
      (is (some? (sut/post-events s {:type :type/first})))
      (is (= [{:type :type/second}]
             @recv)))))

(deftest post-one
  (testing "returns list of events to post"
    (is (= [:new-event]
           (sut/post-one [(constantly :new-event)] [:first-event]))))

  (testing "empty list when no listeners"
    (is (empty? (sut/post-one [] [:test-event]))))

  (testing "flattens event list"
    (is (= [:first :second]
           (sut/post-one [(constantly [:first :second])] [:first-event]))))

  (testing "ignores nil"
    (is (empty? (sut/post-one [(constantly nil)] [:test-event])))))
