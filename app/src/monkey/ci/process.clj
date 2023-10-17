(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka.process :as bp]
            [clojure.core.async :as ca :refer [go <!]]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [config.core :as cc]
            [medley.core :as mc]
            [monkey.ci
             [config :refer [version] :as config]
             [context :as ctx]
             [events :as e]
             [script :as script]
             [utils :as utils]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.web.script-api :as script-api]
            [monkey.socket-async.uds :as uds]))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exits the VM
   with a nonzero value on failure."
  ([args env]
   (let [ctx (config/script-config env args)]
     (log/debug "Executing script with context" ctx)
     (when (bc/failed? (script/exec-script! ctx))
       (System/exit 1))))

  ([args]
   (run args cc/env)))

(defn- local-script-lib-dir
  "Calculates the local script directory as a sibling dir of the
   current (app) directory."
  []
  (-> (.. (io/file (utils/cwd))
          (getAbsoluteFile)
          (getParentFile))
      (io/file "lib")
      (str)))

(defn- generate-deps
  "Generates a string that will be added as a commandline argument
   to the clojure process when running the script.  Any existing `deps.edn`
   should be used as well."
  [{{:keys [script-dir]} :build {:keys [dev-mode]} :args}]
  (when dev-mode
    (log/debug "Running in development mode, using local src instead of libs"))
  (pr-str {:paths [script-dir]
           :aliases
           {:monkeyci/build
            {:exec-fn 'monkey.ci.process/run
             :extra-deps {'com.monkeyci/script
                          (if dev-mode
                            {:local/root (local-script-lib-dir)}
                            {:mvn/version (version)})
                          'com.monkeyci/app
                          (if dev-mode
                            {:local/root (utils/cwd)}
                            {:mvn/version (version)})}
             ;; TODO Only add this if the file actually exists
             :jvm-opts [(str "-Dlogback.configurationFile=" (io/file script-dir "logback.xml"))]}}}))

(defn- build-args
  "Builds command-line args vector for script process"
  [{:keys [build]}]
  (->> (select-keys build [:checkout-dir :script-dir :pipeline])
       (mc/remove-vals nil?)
       (mc/map-keys str)
       (mc/map-vals pr-str)
       (into [])
       (flatten)))

(defn- make-socket-path [build-id]
  (utils/tmp-file (str "events-" build-id ".sock")))

(defn- start-script-api
  "Starts a script API  http server that listens at a domain socket 
   location.  Returns both the server and the socket path."
  [ctx]
  (let [build-id (get-in ctx [:build :build-id])
        path (make-socket-path build-id)]
    {:socket-path path
     :server (script-api/listen-at-socket path ctx)}))

(defn process-env
  "Build the environment to be passed to the child process."
  [ctx socket-path]
  (-> {:api
       {:socket socket-path}}
      (assoc :build-id (get-in ctx [:build :build-id]))
      (merge (select-keys ctx [:containers :log-dir]))
      (config/config->env)))

(defn- get-script-log-dir
  "Determines and creates the log dir for the script output"
  [ctx]
  (when-let [id (get-in ctx [:build :build-id])]
    (doto (io/file (ctx/log-dir ctx) id)
      (.mkdirs))))

(defn- script-output [ctx type]
  (if-let [d (get-script-log-dir ctx)]
    (io/file d (str (name type) ".log"))
    :inherit))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias.  This expects absolute directories.
   Returns an object that can be `deref`ed to wait for the process to exit.  Will
   post a `build/completed` event on process exit."
  [{{:keys [checkout-dir script-dir build-id] :as build} :build bus :event-bus :as ctx}]
  (log/info "Executing build process for" build-id "in" checkout-dir)
  (let [{:keys [socket-path server]} (start-script-api ctx)
        log-dir (get-script-log-dir ctx)]
    (bp/process
     {:dir script-dir
      :out (script-output ctx :out)
      :err (script-output ctx :err)
      :cmd (-> ["clojure"
                "-Sdeps" (generate-deps ctx)
                "-X:monkeyci/build"]
               (concat (build-args ctx))
               (vec))
      :extra-env (process-env ctx socket-path)
      :exit-fn (fn [{:keys [proc] :as p}]
                 (let [exit (or (some-> proc (.exitValue)) 0)]
                   (log/debug "Script process exited with code" exit ", cleaning up")
                   (when server
                     (script-api/stop-server server))
                   (when socket-path 
                     (uds/delete-address socket-path))
                   ;; Bus should always be present.  This check is only for testing purposes.
                   (when bus
                     (log/debug "Posting build/completed event")
                     (e/post-event bus {:type :build/completed
                                        :build build
                                        :exit exit
                                        :result (if (zero? exit) :success :error)
                                        :process p}))))})))
