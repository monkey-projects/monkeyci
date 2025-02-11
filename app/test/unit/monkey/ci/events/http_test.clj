(ns monkey.ci.events.http-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.stream :as ms]
            [monkey.ci.events.http :as sut]
            [monkey.ci.helpers :as h]
            [monkey.mailman.mem :as mmm]))

(deftest event-stream
  (testing "returns response with stream"
    (let [events (h/fake-events)
          r (sut/event-stream events {:key "value"})]
      (is (= 200 (:status r)))
      (is (ms/source? (:body r))))))

(deftest ^:kaocha/skip mailman-stream
  (testing "returns response with stream"
    (let [broker (mmm/make-memory-broker)
          r (sut/mailman-stream broker)]
      (is (= 200 (:status r)))
      (is (ms/source? (:body r))))))

(deftest parse-event-line
  (testing "`nil` if invalid"
    (is (nil? (sut/parse-event-line "invalid"))))

  (testing "returns event parsed from edn"
    (is (= {:key "value"}
           (sut/parse-event-line "data: {:key \"value\"}")))))
