(ns otlp
  (:require [config :as c]
            [monkey.ci.metrics
             [core :as mc]
             [otlp :as mo]
             [prometheus :as mp]]))

(org.slf4j.bridge.SLF4JBridgeHandler/install)

(defn local-conf []
  (:otlp (c/load-config "local.edn")))

(defn run-test [conf]
  ;; Test to see how we can get rid of the 400 "empty data points" error
  ;; returned by otlp server, but no success so far...
  (let [reg (mp/make-registry)
        c (-> (mp/make-counter "test_counter" reg)
              (mp/counter-inc 1))]
    (mo/make-client (:url conf)
                    reg
                    (assoc conf :interval 5))))
