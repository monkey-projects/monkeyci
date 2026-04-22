(ns monkey.ci.test.mailman
  "Functionality for testing mailman events"
  (:require [monkey.ci.protocols :as p]
            [monkey.mailman.core :as mmc]))

(defrecord TestBroker [posted]
  mmc/EventPoster
  (post-events [this events]
    (swap! posted (comp vec concat) events))

  mmc/EventReceiver
  (add-listener [this l]
    ;; Noop
    )

  (poll-events [this n]
    (take n @posted)))

(defn test-broker []
  (->TestBroker (atom [])))

(defrecord TestListener [routes opts]
  mmc/Listener
  (unregister-listener [_]
    ;; noop
    nil))

(defrecord TestComponent [broker]
  p/AddRouter
  (add-router [this r opts]
    [(->TestListener r opts)]))

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
