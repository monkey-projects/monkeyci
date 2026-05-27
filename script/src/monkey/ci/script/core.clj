(ns monkey.ci.script.core
  "Main script running functionality."
  (:require [monkey.ci.script
             [api-client :as ac]
             [config :as c]]
            [monkey.mailman.core-async :as mmca]))

(defn run-script
  "Loads and runs the build script using the given configuration, which contains
   details about where to find the files, how to connect to the build api, etc."
  [conf]
  (let [client (ac/make-client (c/api conf))
        broker (mmca/core-async-broker)]
    {:build (c/build conf)}))
