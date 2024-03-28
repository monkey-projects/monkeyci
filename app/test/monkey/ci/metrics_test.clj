(ns monkey.ci.metrics-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.metrics :as sut]))

(deftest metrics
  (testing "can make metrics registry"
    (is (some? (sut/make-registry))))

  (testing "can scrape"
    (is (string? (sut/scrape (sut/make-registry)))))

  (testing "can start and stop"
    (is (some? (-> (sut/make-registry)
                   (co/start)
                   (co/stop))))))
