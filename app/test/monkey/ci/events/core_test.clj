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

(deftest make-events
  (testing "can make sync events"
    (is (some? (sut/make-events {:events {:type :sync}}))))

  (testing "can make manifold events"
    (is (some? (sut/make-events {:events {:type :manifold}}))))
  
  (testing "can make zeromq server events"
    (is (some? (sut/make-events {:events {:type :zmq
                                          :mode :server
                                          :endpoint "tcp://0.0.0.0:3001"}})))))

(deftest wrapped
  (testing "returns fn that invokes f"
    (let [f (constantly :ok)
          w (sut/wrapped f
                         (constantly nil)
                         (constantly nil))]
      (is (fn? f))
      (is (= :ok (w {})))))

  (testing "posts event before and after invoking f"
    (let [invocations (atom [])
          test-f (fn [t]
                   (fn [& args]
                     (swap! invocations conj (into [t] args))
                     {:event t}))
          poster (fn [evt]
                   (swap! invocations conj [:event evt]))
          w (sut/wrapped (test-f :during)
                         (test-f :before)
                         (test-f :after))]
      (is (= {:event :during}
             (w {:events {:poster poster}} "test-arg")))
      (is (= 5 (count @invocations)))
      (is (= :before (ffirst @invocations)))
      (is (= [:event {:event :before}] (second @invocations)))
      (is (= :during (first (nth @invocations 2))))
      (is (= :after (first (nth @invocations 3))))
      (is (= [:event {:event :after}] (nth @invocations 4)))))

  (testing "invokes `on-error` fn on exception"
    (let [inv (atom [])
          on-error (fn [_ ex]
                     {:exception ex})
          w (sut/wrapped (fn [_]
                           (throw (ex-info "test error" {})))
                         nil
                         nil
                         on-error)
          poster (partial swap! inv conj)]
      (is (thrown? Exception (w {:events {:poster poster}})))
      (is (= 1 (count @inv)))
      (is (= "test error" (some-> @inv
                                  first
                                  :exception
                                  (.getMessage)))))))
