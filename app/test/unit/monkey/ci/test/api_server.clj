(ns monkey.ci.test.api-server
  "Helper functions for working with api servers"
  (:require [manifold.deferred :as md]
            [monkey.ci.build.api-server :as srv]
            [monkey.ci.protocols :as p]
            [monkey.ci.test.runtime :as trt]))

(defrecord EmptyParams []
  p/BuildParams
  (get-build-params [_]
    (md/success-deferred [])))

(defn test-config
  "Creates dummy test configuration for api server"
  []
  (-> (trt/test-runtime)
      (srv/rt->api-server-config)
      (assoc :params (->EmptyParams))))
