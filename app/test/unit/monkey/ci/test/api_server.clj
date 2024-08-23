(ns monkey.ci.test.api-server
  "Helper functions for working with api servers"
  (:require [monkey.ci.build.api-server :as srv]
            [monkey.ci.test.runtime :as trt]))

(defn test-config
  "Creates dummy test configuration for api server"
  []
  (-> (trt/test-runtime)
      (srv/rt->api-server-config)))
