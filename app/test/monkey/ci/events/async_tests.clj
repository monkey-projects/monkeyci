(ns monkey.ci.events.async-tests
  "Generic test suite for asynchronous events"
  (:require [clojure.test :refer [testing is]]
            [monkey.ci.events
             [core :as c]
             [manifold]]
            [monkey.ci.helpers :as h]))

(def timeout 1000)

(defn recv-tests [make-events])

(defn async-tests [make-events]
  (testing "listeners receive events"
    (let [e (make-events)
          evt {:type ::test-event}
          recv (atom [])
          l (c/no-dispatch
             (partial swap! recv conj))]
      (is (= e (c/add-listener e l)))
      (is (= e (c/post-events e evt)))
      (is (not= :timeout (h/wait-until #(pos? (count @recv)) timeout)))
      (is (= [evt] @recv))
      (is (= e (c/remove-listener e l)))
      (is (empty? (reset! recv [])))
      (is (= e (c/post-events e {:type ::other-event})))
      (is (= :timeout (h/wait-until #(pos? (count @recv)) timeout)))))

  (testing "return values of handlers are posted back as events"
    (let [recv (atom [])
          e (-> (make-events)
                (c/add-listener (c/filter-type ::first (constantly {:type ::second})))
                (c/add-listener (->> (partial swap! recv conj)
                                     (c/no-dispatch)
                                     (c/filter-type ::second))))]
      (is (some? (c/post-events e {:type ::first})))
      (is (not= :timeout (h/wait-until #(pos? (count @recv)) timeout)))
      (is (= ::second (-> @recv first :type)))))

  (testing "can post multiple events at once"
    (let [recv (atom [])
          e (-> (make-events)
                (c/add-listener (c/no-dispatch (partial swap! recv conj))))]
      (is (some? (c/post-events e [{:type ::first} {:type ::second}])))
      (is (not= :timeout (h/wait-until #(= 2 (count @recv)) 1000)))
      (is (= [{:type ::first} {:type ::second}] @recv)))))
