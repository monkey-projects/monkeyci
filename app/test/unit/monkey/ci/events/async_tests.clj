(ns monkey.ci.events.async-tests
  "Generic test suite for asynchronous events"
  (:require [clojure.test :refer [testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events
             [core :as c]
             [manifold]]
            [monkey.ci.helpers :as h]))

(def timeout 1000)

(defmacro with-events [[e v] & body]
  `(let [~e (co/start ~v)]
     (try
       ~@body
       (finally
         (co/stop ~e)))))

(defn matches-event? [evt ef]
  (or (nil? ef)
      (= (:type evt) (:type ef))))

(defn async-tests [make-events]
  (testing "listeners receive events"
    (with-events [e (make-events matches-event?)]
      (let [evt {:type ::test-event
                 :message "Test event"}
            ef {:type ::test-event}
            recv (atom [])
            l (partial swap! recv conj)]
        (is (some? (c/add-listener e ef l)))
        (is (some? (c/post-events e evt)))
        (is (not= :timeout (h/wait-until #(pos? (count @recv)) timeout)))
        (is (= [evt] @recv))
        (is (some? (c/remove-listener e ef l)))
        (is (empty? (reset! recv [])))
        (is (some? (c/post-events e {:type ::other-event})))
        (is (= :timeout (h/wait-until #(pos? (count @recv)) timeout))))))

  (testing "events match filter"
    (with-events [e (make-events matches-event?)]
      (let [evt {:type ::test-event
                 :message "Test event"}
            ef {:type ::test-event}
            recv (atom [])
            l (partial swap! recv conj)]
        (is (some? (c/add-listener e ef l)))
        (is (some? (c/post-events e [{:type ::other-event} evt])))
        (is (not= :timeout (h/wait-until #(pos? (count @recv)) timeout)))
        (is (= [evt] @recv)))))

  (testing "can post multiple events at once"
    (with-events [e (make-events matches-event?)]
      (let [recv (atom [])
            e (c/add-listener e nil (partial swap! recv conj))]
        (is (some? (c/post-events e [{:type ::first} {:type ::second}])))
        (is (not= :timeout (h/wait-until #(= 2 (count @recv)) 1000)))
        (is (= [{:type ::first} {:type ::second}] @recv))))))
