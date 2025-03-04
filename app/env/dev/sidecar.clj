(ns sidecar
  (:require [config :as co]
            [monkey.ci.sidecar :as sc]
            [monkey.ci.config.sidecar :as cs]
            [monkey.ci.runners.runtime :as rr]
            [monkey.ci.runtime.sidecar :as rs]))

(defn run-test []
  (rr/with-runner-system @co/global-config
    (fn [sys]
      (let [api (:api-config sys)
            conf (-> {}
                     (cs/set-build (or (:build sys)
                                       {:build-id (str "test-build-" (random-uuid))
                                        :workspace "test-ws"
                                        :checkout-dir "tmp/checkout"}))
                     (cs/set-job {:id "test-job"})
                     (cs/set-events-file "/tmp/events.edn")
                     (cs/set-start-file "/tmp/start")
                     (cs/set-abort-file "/tmp/abort")
                     (cs/set-api {:url (format "http://localhost:%d" (:port api))
                                  :token (:token api)}))]
        @(rs/with-runtime conf sc/run)))))
