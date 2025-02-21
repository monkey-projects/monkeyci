(ns monkey.ci.test.api-server
  "Helper functions for working with api servers"
  (:require [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.build.api-server :as srv]
            [monkey.ci
             [protocols :as p]
             [runtime :as rt]]
            [monkey.ci.test.runtime :as trt]))

(defrecord EmptyParams []
  p/BuildParams
  (get-build-params [_]
    (md/success-deferred [])))

(defn rt->api-server-config
  "Creates a config map for the api server from the given runtime"
  [rt]
  (->> {:port (rt/runner-api-port rt)}
       (merge (select-keys rt [:events :artifacts :cache :workspace :containers]))
       (mc/filter-vals some?)))

(defn test-config
  "Creates dummy test configuration for api server"
  []
  (-> (trt/test-runtime)
      (rt->api-server-config)
      (assoc :params (->EmptyParams))))
