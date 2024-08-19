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
             [edn :as edn]
             [logging :as l]
             [runtime :as rt]
             [script :as script]
             [sidecar]
             [utils :as utils]
             [workspace]]
            [monkey.ci.build.core :as bc]
            ;; Need to require these for the multimethod discovery
            [monkey.ci.containers.oci]
            [monkey.ci.events.core]
            [monkey.ci.storage
             [file]
             [sql]]
            [monkey.ci.web.auth :as auth]))

(def default-script-config
  "Default configuration for the script runner."
  {:containers {:type :podman}
   :storage {:type :memory}
   :logging {:type :inherit}})

(defn exit! [exit-code]
  (System/exit exit-code))

(defn- load-config [{:keys [config-file]}]
  (with-open [r (io/reader config-file)]
    (utils/parse-edn r)))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exits the VM
   with a nonzero value on failure."
  ([args env]
   (log/debug "Running with args:" args)
   (try
     (let [config (load-config args)]
       (when (-> (config/normalize-config (merge default-script-config config)
                                          (config/strip-env-prefix env)
                                          nil)
                 (rt/with-runtime :script rt
                     (log/debug "Executing script with config" (:config rt))
                     (log/debug "Script working directory:" (utils/cwd))
                     (script/exec-script! rt))
                 (bc/failed?))
         (exit! 1)))
     (catch Exception ex
       ;; This could happen if there is an error loading or initializing the child process
       (log/error "Failed to run child process" ex)
       (exit! 1))))

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
  [rt]
  ;; TODO Use some sort of templating engine to generate a custom log config to add
  ;; the build id as a label to the logs (e.g. moustache).  That would make it easier
  ;; to fetch logs for a specific build.
  (->> [(io/file (get-in rt [:build :script-dir]) "logback.xml")
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
                       {:mvn/version (version)}))
        log-config (find-log-config rt)]
    (log/debug "Child process log config:" log-config)
    {:paths [(b/script-dir build)]
     :aliases
     {:monkeyci/build
      (cond-> {:exec-fn 'monkey.ci.process/run
               :extra-deps {'com.monkeyci/app (version-or utils/cwd)}}
        log-config (assoc :jvm-opts
                          [(str "-Dlogback.configurationFile=" log-config)]))}}))

(defn rt->config [build rt]
  (-> (rt/rt->config rt)
      (assoc :build build)
      ;; Generate an API token and add it to the config
      (update :api mc/assoc-some :token (auth/generate-jwt-from-rt rt (auth/build-token (b/sid build))))
      ;; Overwrite event settings with runner-specific config
      (mc/assoc-some :events (get-in rt [rt/config :runner :events]))))

(defn- config->edn
  "Writes the configuration to an edn file that is then passed as command line to the app."
  [build rt]
  (let [f (utils/tmp-file "config" ".edn")]
    (->> (rt->config build rt)
         (edn/->edn)
         (spit f))
    f))

(defn- log-maker [rt]
  (or (rt/log-maker rt) (l/make-logger {})))

(defn- make-logger [rt type]
  (let [build (rt/build rt)
        id (b/build-id build)]
    ((log-maker rt) build [id (str (name type) ".log")])))

(defn- out->str [x]
  (if (instance? java.io.InputStream x)
    (slurp x)
    x))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias.  This expects absolute directories.
   Returns a deferred that will hold the process result when it completes."
  [{:keys [checkout-dir build-id] :as build} rt]
  (log/info "Executing build process for" build-id "in" checkout-dir)
  (let [script-dir (b/script-dir build)
        ;; FIXME Loggers still require the build in the runtime, refactor this
        [out err :as loggers] (map (partial make-logger (assoc rt :build build)) [:out :err])
        result (md/deferred)
        cmd ["clojure"
             "-Sdeps" (pr-str (generate-deps build rt))
             "-X:monkeyci/build"
             (pr-str {:config-file (config->edn build rt)})]]
    (log/debug "Running in script dir:" script-dir ", this command:" cmd)
    ;; TODO Run as another unprivileged user for security
    (-> (bp/process
         {:dir script-dir
          :out (l/log-output out)
          :err (l/log-output err)
          :cmd cmd
          :exit-fn (fn [{:keys [proc out err] :as p}]
                     (let [exit (or (some-> proc (.exitValue)) 0)]
                       (log/debug "Script process exited with code" exit ", cleaning up")
                       (when out
                         (log/debug "Process output:" (out->str out)))
                       (when (and err (not= 0 exit))
                         (log/warn "Process error output:" (out->str err)))
                       (md/success! result {:process p
                                            :exit exit})))})
        ;; Depending on settings, some process streams need handling
        (l/handle-process-streams loggers))
    result))
