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
    (ast/async-tests #(sut/make-zeromq-events {:server
                                               {:enabled true
                                                :addresses [addr]}
                                               :client
                                               {:address addr}}))))

(deftest inproc-server
  (testing "runs inproc client/server on single address"
    (let [ep "inproc://client-test"
          recv (atom [])
          ctx (sut/make-context)
          events (-> (sut/make-zeromq-events {:server
                                              {:enabled true
                                               :addresses [ep]
                                               :context ctx}
                                              :client
                                              {:address ep
                                               :context ctx}})
                     (co/start)
                     (c/add-listener (c/no-dispatch (partial swap! recv conj))))
          evt {:type ::client-test
               :id (random-uuid)}]
      (try
        (is (some? (c/post-events events evt)))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 1000)))
        (is (= [evt] @recv))
        (finally (co/stop events))))))
