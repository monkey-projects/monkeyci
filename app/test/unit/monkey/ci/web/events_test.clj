(ns monkey.ci.web.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold
             [bus :as mb]
             [stream :as ms]]
            [monkey.ci.edn :as edn]
            [monkey.ci.web.events :as sut]))

(defn- parse-sse [evt]
  (let [prefix "data: "]
    (-> evt
        (.strip)
        (subs (count prefix))
        (edn/edn->))))

(deftest bus-stream
  (testing "returns sse stream"
    (let [bus (mb/event-bus)
          r (sut/bus-stream bus [::test-type] (constantly true))]
      (is (= 200 (:status r)))
      (is (ms/stream? (:body r)))))

  (testing "filters by predicate for events"
    (let [bus (mb/event-bus)
          topic ::test-type
          {s :body} (sut/bus-stream bus #{topic} (comp (partial = :ok) :type))
          recv (atom #{})]
      (is (some? (ms/consume (fn [evt]
                               (let [v (-> evt
                                           (parse-sse)
                                           :type)]
                                 (swap! recv conj v)))
                             s)))
      (is (true? (deref (mb/publish! bus topic {:type :other}) 1000 :timeout)))
      (is (true? (deref (mb/publish! bus topic {:type :ok}) 1000 :timeout)))
      (is (nil? (ms/close! s)))
      (is (@recv :ok))
      (is (not (@recv :other)))))

  (testing "receives multiple event types"
    (let [bus (mb/event-bus)
          {s :body} (sut/bus-stream bus #{::type-1 ::type-2} (constantly true))
          recv (atom #{})]
      (is (some? (ms/consume (fn [evt]
                               (let [v (-> evt
                                           (parse-sse)
                                           :type)]
                                 (swap! recv conj v)))
                             s)))
      (doseq [t [::type-1 ::type-2 ::type-3]]
        (deref (mb/publish! bus t {:type t}) 1000 :timeout))
      (is (nil? (ms/close! s)))
      (is (@recv ::type-1))
      (is (@recv ::type-2))
      (is (not (@recv ::type-3))))))

(deftest stream->sse
  (testing "returns sse stream"
    (let [stream (ms/stream)
          r (sut/stream->sse stream (constantly true))]
      (is (= 200 (:status r)))
      (is (ms/stream? (:body r)))))

  (testing "filters by predicate for events"
    (let [stream (ms/stream)
          {s :body} (sut/stream->sse stream (comp (partial = :ok) :type))
          recv (atom #{})]
      (is (some? (ms/consume (fn [evt]
                               (let [v (-> evt
                                           (parse-sse)
                                           :type)]
                                 (swap! recv conj v)))
                             s)))
      (is (true? (deref (ms/put! stream {:type :other}) 1000 :timeout)))
      (is (true? (deref (ms/put! stream {:type :ok}) 1000 :timeout)))
      (is (nil? (ms/close! s)))
      (is (@recv :ok))
      (is (not (@recv :other))))))
