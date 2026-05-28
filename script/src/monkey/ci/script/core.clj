(ns monkey.ci.script.core
  "Main script running functionality."
  (:require [clojure.core.async :as ca]
            [monkey.ci.events.builders :as eb]
            [monkey.ci.script
             [api-client :as ac]
             [build :as b]
             [config :as c]
             [events :as e]
             [interceptors :as i]]
            [monkey.mailman
             [core :as mmc]
             [core-async :as mmca]]))

(defn setup-runner [conf]
  (let [client (ac/make-client (c/api conf))
        broker (mmca/core-async-broker)
        result (ca/chan)
        build (c/build conf)
        routes (e/make-routes
                {:mailman broker
                 :api-client client
                 :result result
                 :build build})
        router (mmc/make-router routes {:executor i/execute})
        listener (mmc/add-listener broker {:handler router})]
    {:build build
     :mailman broker
     :router router
     :listener listener
     :result result}))

(defn run-script
  "Loads and runs the build script using the given configuration, which contains
   details about where to find the files, how to connect to the build api, etc.
   Returns a channel that will hold the result of the build."
  [conf]
  (let [build (c/build conf)
        {:keys [mailman result] :as runner} (setup-runner conf)]
    ;; Kickstart the build script
    (mmc/post-events mailman [(eb/script-init-evt (b/sid build) (b/script-dir build))])
    result))
