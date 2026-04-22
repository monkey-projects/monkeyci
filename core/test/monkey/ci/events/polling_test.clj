(ns monkey.ci.events.polling-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.polling :as sut]
            [monkey.ci.test.mailman :as tm]
            [monkey.mailman
             [core :as mmc]
             [mem :as mem]]))

(deftest poll-next
  (let [broker (mem/make-memory-broker)
        state (atom {:builds 0
                     :handled []})
        router (fn [evt]
                 (swap! state (fn [s]
                                (-> s
                                    (update :builds (fnil inc 0))
                                    (update :handled conj evt))))
                 [])
        conf {:max-builds 1
              :mailman {:broker broker}
              :event-types #{:type/allowed}}
        max-reached? (fn []
                       (= (:max-builds conf) (:builds @state)))]
    
    (testing "handles next allowed event"
      (let [evt {:type :type/allowed
                 :sid ["first"]}]
        (is (some? (mmc/post-events broker [evt])))
        (is (some? (sut/poll-next conf router max-reached?)))
        (is (= [evt] (:handled @state)))))

    (testing "does not take next event if no capacity"
      (let [evt {:type :type/allowed
                 :sid ["second"]}]
        (is (some? (mmc/post-events broker [evt])))
        (is (nil? (sut/poll-next conf router max-reached?)))
        (is (= 1 (count (:handled @state))))))

    (testing "when new capacity, again takes next event"
      (is (some? (swap! state update :builds dec)))
      (is (some? (sut/poll-next conf router max-reached?)))
      (is (= 2 (count (:handled @state)))))

    (testing "ignores types other than allowed"
      (is (some? (mmc/post-events broker [{:type :other}])))
      (is (some? (reset! state {})))
      (is (nil? (sut/poll-next conf router max-reached?)))))

  (testing "posts back resulting events to outgoing broker"
    (let [broker-in (tm/test-component)
          broker-out (tm/test-component)
          router (mmc/router [[:type/allowed [{:handler (constantly [{:type ::second-event}])}]]])]
      (is (some? (mmc/post-events (:broker broker-in) [{:type :type/allowed}])))
      (is (some? (sut/poll-next {:mailman broker-in
                                 :mailman-out broker-out
                                 :event-types #{:type/allowed}}
                                router (constantly false))))
      (is (= [::second-event]
             (map :type (tm/get-posted broker-out)))))))

(deftest poll-loop
  (let [running? (atom true)]
    (testing "polls next until no longer running"
      (let [f (future (sut/poll-loop {:poll-interval 100
                                      :mailman
                                      {:broker ::test-broker}}
                                     (constantly nil)
                                     running?
                                     (constantly true)))] 
        (is (some? f))

        (is (false? (reset! running? false)))
        (is (nil? (deref f 1000 :timeout))
            "expect loop to terminate")))))
