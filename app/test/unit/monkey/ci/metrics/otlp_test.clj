(ns monkey.ci.metrics.otlp-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.metrics
             [core :as mc]
             [otlp :as sut]]))

(deftest make-client
  (testing "creates new client using options"
    (let [c (sut/make-client
             "http://localhost:1234"
             (mc/make-registry)
             {:token "test-token"
              :interval 30
              :service "test-service"})]
      (is (instance? io.prometheus.metrics.exporter.opentelemetry.OpenTelemetryExporter c))
      (is (nil? (.close c))))))
