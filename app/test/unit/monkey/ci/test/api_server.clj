(ns monkey.ci.test.api-server
  "Helper functions for working with api servers"
  (:require [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [protocols :as p]
             [runtime :as rt]]
            [monkey.ci.test.runtime :as trt]))

(defrecord EmptyParams []
  p/BuildParams
  (get-build-params [_ _]
    (md/success-deferred [])))

(defn test-config
  "Creates dummy test configuration for api server"
  []
  (assoc (trt/test-runtime)
         :event-stream (ms/stream 1)
         :params (->EmptyParams)))
