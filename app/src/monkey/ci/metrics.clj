(ns monkey.ci.metrics
  (:require [com.stuartsierra.component :as co]
            [io.resonant.micrometer :as mm]            
            [monkey.ci.runtime :as rt]))

(defn make-registry []
  (mm/meter-registry {:type :prometheus}))

(defn scrape
  "Creates a string that can be used by Prometheus for scraping"
  [r]
  (some-> r :registry (.scrape)))

(defn test-counter []
  (let [invocations (atom 0)]
    (fn [_]
      (swap! invocations inc))))

(defn add-test-counter [r]
  (mm/get-function-counter r "test_counter" {} (test-counter))
  r)

(extend-type io.resonant.micrometer.Registry
  co/Lifecycle
  (start [this]
    ;; TODO Add real metrics
    (add-test-counter this))

  (stop [this]
    (.close this)
    this))

(defmethod rt/setup-runtime :metrics [_ _]
  (make-registry))
