(ns monkey.ci.metrics.otlp-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.metrics
             [core :as mc]
             [otlp :as sut]]
            [monkey.metrics.otlp :as mmo]))

(deftest make-client
  (testing "creates new client using options"
    (with-redefs [mmo/make-client (fn [url reg opts]
                                    (assoc opts :url url :registry reg))]
      (let [c (sut/make-client
               "http://localhost:1234"
               (mc/make-registry)
               {:token "test-token"
                :interval 30
                :service "test-service"})]
        (is (= "http://localhost:1234" (:url c)))
        (is (= {"X-TOKEN" "test-token"}
               (:headers c)))
        (is (some? (:version c))
            "specifies version")))))

