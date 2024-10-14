(ns monkey.ci.metrics-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [io.resonant.micrometer :as mm]
            [monkey.ci.metrics :as sut]
            [monkey.ci.helpers :as h]
            [taoensso.telemere :as t]))

(deftest metrics
  (testing "can make metrics registry"
    (is (some? (sut/make-registry))))

  (testing "can scrape"
    (is (string? (sut/scrape (sut/make-registry)))))

  (testing "can start and stop"
    (is (some? (-> (sut/make-registry)
                   (co/start)
                   (co/stop))))))

(deftest signal->counter
  (testing "creates a gauge that holds signal recv count"
    (let [r (sut/make-registry)
          c (sut/signal->counter ::test-signal r "test_counter" {} {:description "For testing"})
          get-val (fn []
                    (->> (mm/inspect-meter r "test_counter")
                         :measurements
                         (filter (comp (partial = "COUNT") :statistic))
                         (first)
                         :value))]
      (is (some? c))
      (is (true? (t/event! ::test-signal :info)))
      (is (not= :timeout (h/wait-until #(number? (get-val)) 1000)))
      (is (= 1.0 (get-val))))))
