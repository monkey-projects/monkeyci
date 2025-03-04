(ns monkey.ci.test.mailman
  "Functionality for testing mailman events"
  (:require [monkey.ci.events.mailman :as em]
            [monkey.mailman.core :as mmc]))

(defrecord TestBroker [posted]
  mmc/EventPoster
  (post-events [this events]
    (swap! posted (comp vec concat) events))

  mmc/EventReceiver
  (add-listener [this l]
    ;; Noop
    ))

(defn test-broker []
  (->TestBroker (atom [])))

(defrecord TestComponent [broker]
  em/AddRouter
  (add-router [this r opts]
    nil))

(defn test-component []
  (->TestComponent (test-broker)))

(defn- broker-posted [broker]
  (or (:posted broker)
      (get-in broker [:broker :posted])))

(defn get-posted [broker]
  (some-> (broker-posted broker)
          (deref)))

(defn clear-posted! [broker]
  (reset! (broker-posted broker) []))
