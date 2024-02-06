(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
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
             [logging :as l]
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
   (try 
     (let [ctx (config/script-config env args)]
       (log/debug "Executing script with context" ctx)
       (when (bc/failed? (script/exec-script! ctx))
         (System/exit 1)))
     (catch Exception ex
       ;; This could happen if there is an error loading or initializing the child process
       (log/error "Failed to run child process" ex)
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
  [{{:keys [script-dir]} :build :keys [dev-mode]}]
  (when dev-mode
    (log/debug "Running in development mode, using local src instead of libs"))
  (let [version-or (fn [f]
                     (if dev-mode
                       {:local/root (f)}
                       {:mvn/version (version)}))
        log-config (io/file script-dir "logback.xml")]
    (pr-str
     {:paths [script-dir]
      :aliases
      {:monkeyci/build
       (cond-> {:exec-fn 'monkey.ci.process/run
                :extra-deps {'com.monkeyci/script
                             (version-or local-script-lib-dir)
                             'com.monkeyci/app
                             (version-or utils/cwd)}}
         (fs/exists? log-config) (assoc :jvm-opts
                                        [(str "-Dlogback.configurationFile=" log-config)]))}})))

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

(def default-envs
  {:lc-ctype "UTF-8"})

(defn process-env
  "Build the environment to be passed to the child process."
  [ctx socket-path]
  (-> (ctx/ctx->env ctx)
      (assoc :api
             {:socket socket-path})
      (config/config->env)
      (merge default-envs)))

(defn- make-logger [ctx type]
  (let [id (get-in ctx [:build :build-id])]
    ((ctx/log-maker ctx) ctx [id (str (name type) ".log")])))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias.  This expects absolute directories.
   Returns an object that can be `deref`ed to wait for the process to exit.  Will
   post a `build/completed` event on process exit."
  [{{:keys [checkout-dir script-dir build-id] :as build} :build :as ctx}]
  (log/info "Executing build process for" build-id "in" checkout-dir)
  (let [{:keys [socket-path server]} (start-script-api ctx)
        [out err :as loggers] (map (partial make-logger ctx) [:out :err])]
    (-> (bp/process
         {:dir script-dir
          :out (l/log-output out)
          :err (l/log-output err)
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
                       (log/debug "Posting build/completed event")
                       (ctx/post-events ctx (e/build-completed-evt build exit :process p))))})
        ;; Depending on settings, some process streams need handling
        (l/handle-process-streams loggers))))
