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

(defn async-tests [make-events]
  (testing "listeners receive events"
    (with-events [e (make-events)]
      (let [evt {:type ::test-event}
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
        (is (= :timeout (h/wait-until #(pos? (count @recv)) timeout))))))

  (testing "return values of handlers are posted back as events"
    (with-events [e (make-events)]
      (let [recv (atom [])
            e (-> e
                  (c/add-listener (c/filter-type ::first (constantly {:type ::second})))
                  (c/add-listener (->> (partial swap! recv conj)
                                       (c/no-dispatch)
                                       (c/filter-type ::second))))]
        (is (some? (c/post-events e {:type ::first})))
        (is (not= :timeout (h/wait-until #(pos? (count @recv)) timeout)))
        (is (= ::second (-> @recv first :type))))))

  (testing "can post multiple events at once"
    (with-events [e (make-events)]
      (let [recv (atom [])
            e (-> e
                  (c/add-listener (c/no-dispatch (partial swap! recv conj))))]
        (is (some? (c/post-events e [{:type ::first} {:type ::second}])))
        (is (not= :timeout (h/wait-until #(= 2 (count @recv)) 1000)))
        (is (= [{:type ::first} {:type ::second}] @recv))))))
