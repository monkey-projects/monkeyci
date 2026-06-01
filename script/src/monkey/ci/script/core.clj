(ns monkey.ci.script.core
  "Main script running functionality."
  (:require [clojure.core.async :as ca]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
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

(defn connect-events [broker client]
  (let [e (ac/get-events client)]
    (ca/go-loop [r (ca/<! e)]
      (when r
        (log/debug "Dispatching event received from server:" r)
        (mmc/post-events broker [e])))
    ;; Also push all events we're generating
    (mmc/add-listener broker
                      {:handler (fn [evt]
                                  ;; Only push our own events
                                  (when (= :script (:src evt))
                                    (ac/push-events client [evt])
                                    nil))})))

(defn setup-runner [conf]
  (log/debug "Build API url:" (:url (c/api conf)))
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
    (connect-events broker client)
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
    (log/debug "Running script for build:" build)
    ;; Kickstart the build script
    (mmc/post-events mailman [(eb/script-init-evt (b/sid build) (b/script-dir build))])
    result))

(defn- cli->build [args]
  (-> args
      (select-keys [:checkout-dir])
      (merge (zipmap b/sid-props (cs/split (:sid args) #"/")))))

(defn cli->conf [args]
  (-> c/empty-config
      (c/set-api (select-keys args [:url :token]))
      (c/set-build (cli->build args))))

(defn run-cli
  "Convenience function that allows a script to be executed from the command line 
   (using babashka tasks).  It reads command line args and converts it into a valid
   configuration."
  {:org.babashka/cli {:alias {:t :token
                              :u :url}}}
  [cli-args]
  (log/debug "Running script from cli with args:" cli-args)
  ;; TODO Return nonzero on error
  (ca/<!! (run-script (cli->conf cli-args))))
