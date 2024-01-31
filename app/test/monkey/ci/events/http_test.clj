(ns monkey.ci.events.http-test
  (:require [monkey.ci.events
             [async-tests :as ast]
             [http :as sut]]
            [clojure.test :refer [deftest testing is]]))

(deftest http-client-events
  (ast/async-tests #(sut/make-http-client "http://localhost:1234")))

(deftest socket-client-events
  (ast/async-tests #(sut/make-socket-client "test.sock")))
