(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [config.core :as cc]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [config :refer [version] :as config]
             [logging :as l]
             [runtime :as rt]
             [script :as script]
             [utils :as utils]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.web.script-api :as script-api]
            [monkey.socket-async.uds :as uds]))

(def default-script-config
  "Default configuration for the script runner."
  {:containers {:type :podman}
   :storage {:type :memory}
   :logging {:type :inherit}})

(defn exit! [exit-code]
  (System/exit exit-code))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exits the VM
   with a nonzero value on failure."
  ([args env]
   (try 
     (let [rt (-> (config/normalize-config default-script-config (config/strip-env-prefix env) args)
                  (rt/config->runtime))]
       (log/debug "Executing script with runtime" rt)
       (when (bc/failed? (script/exec-script! rt))
         (exit! 1)))
     (catch Exception ex
       ;; This could happen if there is an error loading or initializing the child process
       (log/error "Failed to run child process" ex)
       (exit! 1))))

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
  [{{:keys [script-dir]} :build :as rt}]
  (when (rt/dev-mode? rt)
    (log/debug "Running in development mode, using local src instead of libs"))
  (let [version-or (fn [f]
                     (if (rt/dev-mode? rt)
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
  [rt]
  (let [build-id (get-in rt [:build :build-id])
        path (make-socket-path build-id)]
    {:socket-path path
     :server (script-api/listen-at-socket path rt)}))

(def default-envs
  {:lc-ctype "UTF-8"})

(defn process-env
  "Build the environment to be passed to the child process."
  [rt socket-path]
  (-> (rt/rt->env rt)
      (assoc :api
             {:socket socket-path})
      (config/config->env)
      (merge default-envs)))

(defn- log-maker [rt]
  (or (rt/log-maker rt) (l/make-logger {})))

(defn- make-logger [rt type]
  (let [id (get-in rt [:build :build-id])]
    ((log-maker rt) rt [id (str (name type) ".log")])))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias.  This expects absolute directories.
   Returns a deferred that will hold the process result when it completes."
  [{{:keys [checkout-dir script-dir build-id] :as build} :build :as rt}]
  (log/info "Executing build process for" build-id "in" checkout-dir)
  (let [{:keys [socket-path server]} (start-script-api rt)
        [out err :as loggers] (map (partial make-logger rt) [:out :err])
        result (md/deferred)]
    (-> (bp/process
         {:dir script-dir
          :out (l/log-output out)
          :err (l/log-output err)
          :cmd (-> ["clojure"
                    "-Sdeps" (generate-deps rt)
                    "-X:monkeyci/build"]
                   (concat (build-args rt))
                   (vec))
          :extra-env (process-env rt socket-path)
          :exit-fn (fn [{:keys [proc] :as p}]
                     (let [exit (or (some-> proc (.exitValue)) 0)]
                       (log/debug "Script process exited with code" exit ", cleaning up")
                       (when server
                         (script-api/stop-server server))
                       (when socket-path 
                         (uds/delete-address socket-path))
                       (log/debug "Posting build/completed event")
                       (-> (b/build-completed-result build exit)
                           (assoc :process p)
                           (as-> r (md/success! result r)))))})
        ;; Depending on settings, some process streams need handling
        (l/handle-process-streams loggers))
    result))
