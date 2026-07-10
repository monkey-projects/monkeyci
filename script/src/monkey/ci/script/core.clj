(ns monkey.ci.script.core
  "Main script running functionality."
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
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
        (mmc/post-events broker [r])
        (recur (ca/<! e))))
    ;; Also push all events we're generating
    (mmc/add-listener broker
                      {:handler (fn [evt]
                                  ;; Only push our own events
                                  (when (= :script (:src evt))
                                    (ac/push-events client [evt])
                                    nil))})))

(defn- api-client-file [client f]
  (fn [ctx art]
    (log/debug "Invoking" f "for job" (get-in ctx [:job :id]) "and artifact" art)
    (f client (get-in ctx [:job :id]) (:id art) (:path art))))

(defn artifact-saver
  "Creates a function that is capable of saving artifacts for action jobs."
  [client]
  (api-client-file client ac/put-artifact))

(defn artifact-restorer
  "Creates a function that is capable of restoring artifacts for action jobs."
  [client]
  (api-client-file client ac/get-artifact))

(defn cache-saver
  "Creates a function that is capable of saving caches for action jobs."
  [client]
  (api-client-file client ac/put-cache))

(defn cache-restorer
  "Creates a function that is capable of restoring caches for action jobs."
  [client]
  (api-client-file client ac/get-cache))

(defn setup-runner [conf]
  (log/info "Build API url:" (:url (c/api conf)))
  (let [client (ac/make-client (c/api conf))
        broker (mmca/core-async-broker)
        result (ca/chan)
        build (c/build conf)
        ;; TODO Caches and artifacts
        routes (e/make-routes
                {:mailman broker
                 :api-client client
                 :result result
                 :build build
                 :cache {:save (cache-saver client)
                         :restore (cache-restorer client)}
                 :artifact {:save (artifact-saver client)
                            :restore (artifact-restorer client)}})
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
      (mc/update-existing :checkout-dir (comp str fs/canonicalize)) 
      (merge (zipmap b/sid-props (cs/split (:sid args) #"/")))))

(defn cli->conf [args]
  (-> c/empty-config
      (c/set-api (select-keys args [:url :token]))
      (c/set-build (cli->build args))))

(defn run-bb-cli
  "Convenience function that allows a script to be executed from the command line 
   (using babashka tasks).  It reads command line args and converts it into a valid
   configuration."
  {:org.babashka/cli {:alias {:t :token
                              :u :url}}}
  [cli-args]
  (log/debug "Running script from cli with args:" cli-args)
  (let [r (ca/<!! (run-script (cli->conf cli-args)))]
    (when (b/failed? r)
      (System/exit 1))))

(defn run-clj-cli
  "Provided as an entrypoint for clj invocations"
  [{:keys [config]}]
  (let [r (ca/<!! (run-script config))]
    (when (b/failed? r)
      (System/exit 1))))
