(ns monkey.ci.events.http-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold
             [bus :as mb]
             [stream :as ms]]
            [monkey.ci.edn :as edn]
            [monkey.ci.events.http :as sut]
            [monkey.ci.helpers :as h]
            [monkey.mailman
             [core :as mmc]
             [mem :as mmm]]))

(defn- parse-sse [evt]
  (let [prefix "data: "]
    (-> evt
        (.strip)
        (subs (count prefix))
        (edn/edn->))))

(deftest event-stream
  (testing "returns response with stream"
    (let [events (h/fake-events)
          r (sut/event-stream events {:key "value"})]
      (is (= 200 (:status r)))
      (is (ms/source? (:body r))))))

(deftest mailman-stream
  (testing "returns response with stream"
    (let [broker (mmm/make-memory-broker)
          r (sut/mailman-stream broker nil)]
      (is (= 200 (:status r)))
      (is (ms/source? (:body r)))
      (is (nil? (ms/close! (:body r))))))

  (testing "puts events that match tx on stream"
    (let [broker (mmm/make-memory-broker)
          {s :body} (sut/mailman-stream broker (comp (partial = :first) :type))
          recv (atom [])]
      (is (some? (ms/consume (fn [evt]
                               (let [v (-> evt
                                           (parse-sse)
                                           :type)]
                                 (swap! recv conj v)))
                             s)))
      (is (some? (mmc/post-events broker [{:type :other} {:type :first}])))
      (is (not= :timeout (h/wait-until #((set @recv) :first) 1000)))
      (is (nil? (ms/close! s)))
      (is (not ((set @recv) :other))))))

(deftest bus-stream
  (testing "returns sse stream"
    (let [bus (mb/event-bus)
          r (sut/bus-stream bus ::test-type (constantly true))]
      (is (= 200 (:status r)))
      (is (ms/stream? (:body r)))))

  (testing "filters by predicate for events"
    (let [bus (mb/event-bus)
          topic ::test-type
          {s :body} (sut/bus-stream bus topic (comp (partial = :ok) :type))
          recv (atom #{})]
      (is (some? (ms/consume (fn [evt]
                               (let [v (-> evt
                                           (parse-sse)
                                           :type)]
                                 (swap! recv conj v)))
                             s)))
      (is (true? (deref (mb/publish! bus topic {:type :other}) 1000 :timeout)))
      (is (true? (deref (mb/publish! bus topic {:type :ok}) 1000 :timeout)))
      (is (not= :timeout (h/wait-until #(@recv :ok) 1000)))
      (is (nil? (ms/close! s)))
      (is (not (@recv :other))))))

(deftest parse-event-line
  (testing "`nil` if invalid"
    (is (nil? (sut/parse-event-line "invalid"))))

  (testing "returns event parsed from edn"
    (is (= {:key "value"}
           (sut/parse-event-line "data: {:key \"value\"}")))))
