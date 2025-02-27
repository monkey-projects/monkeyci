(ns monkey.ci.events.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events
             [core :as sut]
             [async-tests :as ast]]))

(deftest make-event
  (testing "adds timestamp to event"
    (is (number? (-> (sut/make-event :test-event :key "value")
                     :time))))

  (testing "adds properties from varargs"
    (is (= {:key "value"} (-> (sut/make-event :test-event :key "value")
                              (select-keys [:key])))))

  (testing "adds properties from map"
    (is (= {:key "value"} (-> (sut/make-event :test-event {:key "value"})
                              (select-keys [:key]))))))

(deftest sync-events
  (testing "can add listener"
    (let [s (sut/make-sync-events ast/matches-event?)
          handler (constantly nil)]
      (is (= s (sut/add-listener s nil handler)))
      (is (= 1 (count @(.listeners s))))
      (is (= {nil [handler]} @(.listeners s)))))

  (testing "can remove listener"
    (let [l (constantly nil)
          ef {:type :test-event}]
      (is (empty? (-> (sut/make-sync-events ast/matches-event?)
                      (sut/add-listener ef l)
                      (sut/remove-listener ef l)
                      (.listeners)
                      (deref)
                      (get ef))))))

  (testing "can dispatch single event"
    (let [recv (atom [])
          s (sut/make-sync-events ast/matches-event?)
          evt {:type :test-event}]
      (is (some? (sut/add-listener s {:type :test-event} (partial swap! recv conj))))
      (is (= s (sut/post-events s evt)))
      (is (= [evt] @recv)))))

(deftest make-events
  (testing "can make sync events"
    (is (some? (sut/make-events {:type :sync}))))

  (testing "can make manifold events"
    (is (some? (sut/make-events {:type :manifold})))))

(deftest matches-event?
  (testing "matches event that contains one of the specified types"
    (let [ef {:types #{::test-type}}]
      (is (true? (sut/matches-event? {:type ::test-type} ef)))
      (is (false? (sut/matches-event? {:type ::other-type} ef)))))

  (testing "matches all events when `nil` filter"
    (is (true? (sut/matches-event? {:type ::some-type} nil))))

  (testing "matches by partial sid"
    (let [ef {:sid ["test-cust"]}]
      (is (true? (sut/matches-event? {:sid ["test-cust" "test-repo"]} ef)))
      (is (false? (sut/matches-event? {:type ::other} ef)))))

  (testing "matches both by sid and type"
    (let [ef {:sid ["test-cust"]
              :types #{::accepted-type}}]
      (is (sut/matches-event? {:sid ["test-cust" "test-repo"]
                               :type ::accepted-type}
                              ef))
      (is (not (sut/matches-event? {:sid ["other-cust" "tet-repo"]
                                    :type ::accepted-type}
                                   ef)))
      (is (not (sut/matches-event? {:sid ["test-cust" "tet-repo"]
                                    :type ::other-type}
                                   ef))))))

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
                   (swap! invocations conj [:event (first evt)]))
          w (sut/wrapped (test-f :during)
                         (test-f :before)
                         (test-f :after))]
      (is (= {:event :during}
             (w {:events {:poster poster}} "test-arg")))
      (is (= 5 (count @invocations)))
      (is (= :before (ffirst @invocations)))
      (is (= {:event :before} (-> (second @invocations) second (select-keys [:event]))))
      (is (= :during (first (nth @invocations 2))))
      (is (= :after (first (nth @invocations 3))))
      (is (= {:event :after} (-> (nth @invocations 4) second (select-keys [:event]))))))

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
                                  ffirst
                                  :exception
                                  (.getMessage)))))))

