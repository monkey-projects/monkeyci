(ns monkey.ci.events.jms-test
  (:require [monkey.ci.events
             [async-tests :as ast]
             [core :as ec]
             [jms :as sut]]
            [clojure.test :refer [deftest testing is]]))

(def default-config
  {:server
   {:enabled true
    :url "tcp://0.0.0.0:4001"}
   :client
   {:url "tcp://localhost:4001"
    :dest "/topic/test"}})

(deftest jms-events
  (ast/async-tests (partial sut/make-jms-events default-config)))
