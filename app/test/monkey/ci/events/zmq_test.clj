(ns monkey.ci.events.zmq-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events
             [async-tests :as ast]
             [core :as c]
             [zmq :as sut]]
            [monkey.ci.helpers :as h]))

(deftest event-server
  (ast/async-tests #(sut/make-zeromq-events {:mode :server
                                             :endpoint "inproc://server-test"})))

(defn- stop-all [components]
  (doseq [c components]
    (co/stop c)))

(deftest event-client
  (testing "posts events to server"
    (let [ep "inproc://client-test"
          events (atom [])
          ctx (sut/make-context)
          server (-> (sut/make-zeromq-events {:mode :server
                                              :endpoint ep
                                              :context ctx})
                     (c/add-listener (c/no-dispatch (partial swap! events conj)))
                     (co/start))
          client (-> (sut/make-zeromq-events {:mode :client
                                              :endpoint ep
                                              :context ctx})
                     (co/start))
          evt {:type ::client-test
               :id (random-uuid)}]
      (try
        (is (some? (c/post-events client evt)))
        (is (not= :timeout (h/wait-until #(not-empty @events) 1000)))
        (is (= [evt] @events))
        (finally (stop-all [client server]))))))
