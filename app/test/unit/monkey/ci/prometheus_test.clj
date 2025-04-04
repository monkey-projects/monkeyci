(ns monkey.ci.prometheus-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci.prometheus :as sut]))

(deftest make-registry
  (testing "creates a new Prometheus registry"
    (is (instance? io.prometheus.metrics.model.registry.PrometheusRegistry (sut/make-registry)))))

(deftest scrape
  (testing "returns metrics as string format"
    (let [reg (sut/make-registry)
          gauge (-> (sut/make-gauge "test_val" reg)
                    (sut/gauge-set 100))]
      (is (cs/includes? (sut/scrape reg) "100")))))

(deftest counter
  (testing "can increase"
    (let [reg (sut/make-registry)
          c (sut/make-counter "test_counter" reg {:labels ["test" "labels"]})]
      (is (= c (sut/counter-inc c 10 ["val-1" "val-2"])))
      (is (= 10.0 (sut/counter-get c ["val-1" "val-2"])))))

  (testing "invokes callback if provided"
    (let [reg (sut/make-registry)
          c (sut/make-counter "lazy_counter" reg {:callback (constantly 7)})]
      (is (= 7.0 (.. c (collect) (getDataPoints) (get 0) (getValue))))))

  (testing "invokes callback with labels"
    (let [reg (sut/make-registry)
          c (sut/make-counter "lazy_counter" reg {:callback (constantly [7 "test-domain"])
                                                  :labels ["domain"]})]
      (is (= "test-domain" (.. c
                               (collect) (getDataPoints) (get 0)
                               (getLabels) (getValue 0)))))))

(deftest push-gw
  (testing "creates push gateway"
    (is (instance? io.prometheus.metrics.exporter.pushgateway.PushGateway
                   (sut/push-gw "test-host" 9091 (sut/make-registry) "test_job")))))

