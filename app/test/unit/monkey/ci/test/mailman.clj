(ns monkey.ci.test.mailman
  "Functionality for testing mailman events"
  (:require [monkey.mailman.core :as mmc]))

(defrecord TestBroker [posted]
  mmc/EventPoster
  (post-events [this events]
    (swap! posted (comp vec concat) events)))

(defn get-posted [broker]
  @(:posted broker))

(defn test-broker []
  (->TestBroker (atom [])))
