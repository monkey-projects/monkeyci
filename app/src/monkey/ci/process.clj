(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [config.core :as cc]
            [manifold
             [deferred :as md]
             [executor :as me]]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [config :as config]
             [edn :as edn]
             [errors :as err]
             [logging :as l]
             [runtime :as rt]
             [script :as script]
             [sidecar]
             [utils :as utils]
             [version :as v]]
            [monkey.ci.build
             [api-server :as as]
             [core :as bc]]
            [monkey.ci.config.script :as cos]
            ;; Need to require these for the multimethod discovery
            [monkey.ci.containers.oci]
            [monkey.ci.events.core]
            [monkey.ci.runtime.script :as rs]            
            [monkey.ci.storage
             [file]
             [sql]]))

(def default-script-config
  "Default configuration for the script runner."
  {:containers {:type :build-api}})

(defn exit! [exit-code]
  (System/exit exit-code))

(defn- load-config [{:keys [config-file]}]
  (config/load-config-file config-file))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exits the VM
   with a nonzero value on failure."
  ([args env]
   (log/debug "Running with args:" args)
   (try
     (let [config (load-config args)]
       (when (-> (merge default-script-config config)
                 (rs/with-runtime
                   (fn [rt]
                     (log/debug "Executing script with config" (:config rt))
                     (log/debug "Script working directory:" (utils/cwd))
                     (script/exec-script! rt)))
                 (bc/failed?))
         (exit! err/error-script-failure)))
     (catch Throwable ex
       ;; This could happen if there is an error loading or initializing the child process
       (log/error "Failed to run script process" ex)
       (exit! err/error-process-failure))))

  ([args]
   (run args cc/env)))

(defn verify
  "Run function that verifies the build by attempting to load the build script using
   some given configuration."
  ([args env]
   ;; TODO
   )
  ([args]
   (verify args cc/env)))

(defn- find-log-config
  "Finds logback configuration file, either configured on the runner, or present 
   in the script dir"
  [build rt]
  ;; TODO Use some sort of templating engine to generate a custom log config to add
  ;; the build id as a label to the logs (e.g. moustache).  That would make it easier
  ;; to fetch logs for a specific build.
  ;; FIXME When using aero tags, it could be that instead of a path, we have the file
  ;; contents instead.  In that case we should write it to a tmp file.
  (->> [(io/file (get-in build [:script :script-dir]) "logback.xml")
        (some->> (get-in rt [:config :runner :log-config])
                 (utils/abs-path (rt/work-dir rt)))]
       (filter fs/exists?)
       (first)))

(defn generate-deps
  "Generates a string that will be added as a commandline argument
   to the clojure process when running the script.  Any existing `deps.edn`
   should be used as well."
  [build rt]
  (when (rt/dev-mode? rt)
    (log/debug "Running in development mode, using local src instead of libs"))
  (let [version-or (fn [f]
                     (if (rt/dev-mode? rt)
                       {:local/root (f)}
                       {:mvn/version (v/version)}))
        log-config (find-log-config build rt)]
    (log/debug "Child process log config:" log-config)
    {:paths [(b/script-dir build)]
     :aliases
     {:monkeyci/build
      (cond-> {:exec-fn 'monkey.ci.process/run
               :extra-deps {'com.monkeyci/app (version-or utils/cwd)}}
        log-config (assoc :jvm-opts
                          [(str "-Dlogback.configurationFile=" log-config)]))}}))

(defn child-config
  "Creates a configuration map that can then be passed to the child process."
  [build api-conf]
  (-> cos/empty-config
      (cos/set-build build)
      (cos/set-api (as/srv->api-config api-conf))))

(defn- config->edn
  "Writes the configuration to an edn file that is then passed as command line to the app."
  [config]
  (let [f (utils/tmp-file "config" ".edn")]
    (->> config
         (edn/->edn)
         (spit f))
    f))

(defn- log-maker [rt]
  (or (rt/log-maker rt) (l/make-logger {})))

(defn- make-logger [rt build type]
  (let [id (b/build-id build)]
    ((log-maker rt) build [id (str (name type) ".log")])))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias.  This expects absolute directories.
   Returns a deferred that will hold the process result when it completes."
  [{:keys [checkout-dir build-id] :as build} rt]
  (log/info "Executing build process for" build-id "in" checkout-dir)
  (let [script-dir (b/script-dir build)
        [out err :as loggers] (map (partial make-logger rt build) [:out :err])
        result (md/deferred)
        cmd ["clojure"
             "-Sdeps" (pr-str (generate-deps build rt))
             "-X:monkeyci/build"
             (pr-str {:config-file (config->edn (child-config build (:api-config rt)))})]]
    (log/debug "Running in script dir:" script-dir ", this command:" cmd)
    (rt/post-events rt [(script/script-init-evt build script-dir)])
    ;; TODO Run as another unprivileged user for security (we'd need `su -c` for that)
    ;; TODO Restore and save m2 cache using deps.edn hash as key
    (-> (bp/process
         {:dir script-dir
          :out (l/log-output out)
          :err (l/log-output err)
          :cmd cmd
          :exit-fn (fn [{:keys [proc out err] :as p}]
                     (let [exit (or (some-> proc (.exitValue)) 0)]
                       (log/debug "Script process exited with code" exit ", cleaning up")
                       (when out
                         (log/debug "Process output:" (bs/to-string out)))
                       (when (and err (not= 0 exit))
                         (log/warn "Process error output:" (bs/to-string err)))
                       (md/success! result (cond-> {:process p
                                                    :exit exit}
                                             (= err/error-process-failure exit)
                                             (assoc :message "Child process failed to initialize correctly")))))})
        ;; Depending on settings, some process streams need handling
        (l/handle-process-streams loggers))
    result))
