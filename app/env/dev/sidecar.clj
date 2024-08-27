(ns sidecar
  (:require [config :as co]
            [monkey.ci
             [commands :as cmd]
             [config :as c]
             [runtime :as rt]
             [sidecar :as sc]]))

(defn run-test []
  (let [build {:build-id "test-build"
               :workspace "test-ws"}
        job {:id "test-job"}]
    (rt/with-runtime-fn (-> @co/global-config
                            (assoc :build build)
                            (c/normalize-config {} {:events-file "/tmp/events.edn"
                                                    :start-file "/tmp/start"
                                                    :abort-file "/tmp/abort"
                                                    :job-config {:job job}}))
      :sidecar
      cmd/sidecar)))
