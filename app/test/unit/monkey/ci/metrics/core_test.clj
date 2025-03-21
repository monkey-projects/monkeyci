(ns monkey.ci.metrics.core-test
  (:require [clojure
             [string :as cs]
             [test :refer [deftest is testing]]]
            [com.stuartsierra.component :as co]
            [monkey.ci.metrics.core :as sut]
            [monkey.ci.prometheus :as prom]
            [monkey.ci.test.helpers :as h]
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

(deftest counter-id
  (testing "builds metrics name from parts"
    (is (= "monkeyci_test_metric" (sut/counter-id [:test :metric])))))

(deftest signal->counter
  (testing "creates a gauge that holds signal recv count"
    (try
      (let [r (sut/make-registry)
            c (sut/signal->counter ::test-handler r "test_counter"
                                   {:opts {:description "For testing"}})]
        (is (some? c))
        (is (nil? (t/event! ::test-signal :info)))
        (is (not= :timeout (h/wait-until (comp (every-pred number? pos?) #(prom/counter-get c)) 1000)))
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
        (is (nil? (t/event! ::ok-signal :info)))
        (is (nil? (t/event! ::other-signal :info)))
        (is (not= :timeout (h/wait-until (comp (every-pred number? pos?) #(prom/counter-get c)) 1000)))
        (is (= 1.0 (prom/counter-get c))))
      (finally
        (t/remove-handler! ::filter-handler))))

  (testing "adds tags as labels"
    (try
      (let [r (sut/make-registry)
            c (sut/signal->counter ::lbl-handler r "filter_counter"
                                   {:opts {:description "For testing"}
                                    :tags (fn
                                            ([]
                                             ["test_lbl"])
                                            ([s]
                                             [(get-in s [:data :lbl])]))})
            get-val (fn []
                      (prom/counter-get c ["test-val"]))]
        (is (some? c))
        (is (nil? (t/event! ::lbl-signal {:level :info :data {:lbl "test-val"}})))
        (is (not= :timeout (h/wait-until (comp (every-pred number? pos?) get-val) 1000)))
        (is (= 1.0 (get-val))))
      (finally
        (t/remove-handler! ::lbl-handler)))))

(deftest metrics-component
  (let [co (-> (sut/make-metrics)
               (co/start))]
    (try
      (testing "creates registry at start"
        (is (some? (:registry co))))

      (testing "handles oci invocation metrics"
        (is (= :oci/invocation
               (-> (t/with-signal
                     (t/event! :oci/invocation
                               {:data {:kind :test-event}
                                :level :info}))
                   :id)))
        (is (not= :timeout (h/wait-until #(cs/includes? (sut/scrape (:registry co)) "monkeyci_oci_calls")
                                         1000))))
      
      (finally
        (co/stop co)))))
