(ns monkey.ci.events.manifold-test
  (:require [monkey.ci.events
             [async-tests :as ast]
             [manifold :as sut]]
            [clojure.test :refer [deftest]]))

(deftest manifold-events
  (ast/async-tests sut/make-events))