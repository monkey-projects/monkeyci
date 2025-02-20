(ns monkey.ci.test.mailman
  "Functionality for testing mailman events"
  (:require [monkey.mailman.core :as mmc]))

(defrecord TestBroker [posted]
  mmc/EventPoster
  (post-events [this events]
    (swap! posted (comp vec concat) events)))

(defn test-broker []
  (->TestBroker (atom [])))

(defn test-component []
  {:broker (test-broker)})

(defn get-posted [broker]
  (some-> (or (:posted broker)
              (get-in broker [:broker :posted]))
          (deref)))
