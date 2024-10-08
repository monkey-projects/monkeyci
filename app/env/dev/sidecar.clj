(ns sidecar
  (:require [config :as co]
            [monkey.ci
             [commands :as cmd]
             [config :as c]
             [runtime :as rt]
             [sidecar :as sc]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.config.sidecar :as cs]
            [monkey.ci.runtime
             [app :as ra]
             [sidecar :as rs]]))

(defn run-test-legacy []
  (let [build {:build-id "test-build"
               :workspace "test-ws"}
        job {:id "test-job"}]
    (rt/with-runtime-fn (-> @co/global-config
                            (assoc :build build
                                   :sidecar {:events-file "/tmp/events.edn"
                                             :start-file "/tmp/start"
                                             :abort-file "/tmp/abort"
                                             :job-config {:job job}}))
      :sidecar
      cmd/sidecar)))

(defn api-server-config []
  (rt/with-runtime @co/global-config :repl rt
    (bas/rt->api-server-config rt)))

(defn run-test []
  (ra/with-runner-system @co/global-config
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
