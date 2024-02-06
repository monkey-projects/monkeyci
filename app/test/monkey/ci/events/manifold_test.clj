(ns monkey.ci.events.manifold-test
  (:require [monkey.ci.events
             [core :as c]
             [async-tests :as ast]
             [manifold :as sut]]
            [clojure.test :refer [deftest testing is]]))

(deftest manifold-events
  (ast/async-tests sut/make-manifold-events))

(deftest make-events
  (testing "can make manifold events"
    (is (some? (c/make-events {:events {:type :manifold}})))))
