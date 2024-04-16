(ns sidecar
  (:require [config :as co]
            [monkey.ci
             [config :as c]
             [runtime :as rt]
             [sidecar :as sc]]))

(defn run-test []
  (let [rt (-> @co/global-config
               (c/normalize-config {} {})
               (rt/config->runtime)
               (assoc-in [:config :args] {:events-file "/tmp/events.edn"
                                          :start-file "/tmp/start"}))]
    (sc/run rt)))
