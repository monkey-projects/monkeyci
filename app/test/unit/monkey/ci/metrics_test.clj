(ns monkey.ci.metrics-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [metrics :as sut]
             [prometheus :as prom]]
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
    (try
      (let [r (sut/make-registry)
            c (sut/signal->counter ::test-handler r "test_counter"
                                   {:opts {:description "For testing"}})]
        (is (some? c))
        (is (true? (t/event! ::test-signal :info)))
        (is (not= :timeout (h/wait-until #(number? (prom/counter-get c)) 1000)))
        (is (= 1.0 (prom/counter-get c))))
      (finally
        (t/remove-handler! ::test-handler))))

  (testing "applies transducer to signal"
    (try
      (let [r (sut/make-registry)
            c (sut/signal->counter ::filter-handler r "filter_counter"
                                   {:opts {:description "For testing"}
                                    :tx (filter (comp (partial = ::ok-signal) :id))})]
        (is (some? c))
        (is (true? (t/event! ::ok-signal :info)))
        (is (true? (t/event! ::other-signal :info)))
        (is (not= :timeout (h/wait-until #(number? (prom/counter-get c)) 1000)))
        (is (= 1.0 (prom/counter-get c))))
      (finally
        (t/remove-handler! ::filter-handler)))))
