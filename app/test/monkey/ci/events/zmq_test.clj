(ns monkey.ci.events.zmq-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events
             [async-tests :as ast]
             [zmq :as sut]]))

(deftest event-server
  (testing "with single endpoint"
    (ast/async-tests #(sut/make-zeromq-events {:mode :server
                                               :endpoint "inproc://test"}))))
