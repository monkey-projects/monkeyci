(ns monkey.ci.events.zmq-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events
             [async-tests :as ast]
             [core :as c]
             [zmq :as sut]]
            [monkey.ci.helpers :as h]))

(deftest network-server
  (let [addr "tcp://0.0.0.0:3100"]
    (ast/async-tests (partial sut/make-zeromq-events
                              {:server
                               {:enabled true
                                :addresses [addr]}
                               :client
                               {:address addr}}))))

(deftest inproc-server
  (testing "runs inproc client/server on single address"
    (let [ep "inproc://client-test"
          recv (atom [])
          events (-> (sut/make-zeromq-events {:server
                                              {:enabled true
                                               :addresses [ep]}
                                              :client
                                              {:address ep}}
                                             ast/matches-event?)
                     (co/start)
                     (c/add-listener nil (partial swap! recv conj)))
          evt {:type ::client-test
               :id (random-uuid)}]
      (try
        (is (some? (c/post-events events evt)))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 1000)))
        (is (= [evt] @recv))
        (finally (co/stop events))))))
