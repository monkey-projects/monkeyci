(ns monkey.ci.test.api-server
  "Helper functions for working with api servers"
  (:require [manifold
             [bus :as mb]
             [deferred :as md]]
            [monkey.ci
             [protocols :as p]
             [runtime :as rt]]
            [monkey.ci.test.runtime :as trt]))

(defrecord EmptyParams []
  p/BuildParams
  (get-build-params [_]
    (md/success-deferred [])))

(defn test-config
  "Creates dummy test configuration for api server"
  []
  (let [rt (trt/test-runtime)]
    (assoc rt
           :event-bus (mb/event-bus)
           :params (->EmptyParams))))
