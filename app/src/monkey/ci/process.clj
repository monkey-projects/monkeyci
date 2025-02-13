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
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [config :as config]
             [edn :as edn]
             [errors :as err]
             [logging :as l]
             [retry :as retry]
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

(defn- load-config
  "Either loads config from a config file, or passed directly as an exec arg"
  [{:keys [config config-file]}]
  (or config (config/load-config-file config-file)))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exits the VM
   with a nonzero value on failure."
  [args & _]
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

(def m2-cache-dir "/tmp/m2")

(defn- version-or [rt f]
  (if (rt/dev-mode? rt)
    {:local/root (f)}
    {:mvn/version (v/version)}))

(defn generate-deps
  "Generates a string that will be added as a commandline argument
   to the clojure process when running the script.  Any existing `deps.edn`
   should be used as well."
  [build rt]
  (when (rt/dev-mode? rt)
    (log/debug "Running in development mode, using local src instead of libs"))
  (let [log-config (find-log-config build rt)]
    (log/debug "Child process log config:" log-config)
    {:paths [(b/script-dir build)]
     :aliases
     {:monkeyci/build
      (cond-> {:exec-fn 'monkey.ci.process/run
               :extra-deps {'com.monkeyci/app (version-or rt utils/cwd)}}
        log-config (assoc :jvm-opts
                          [(str "-Dlogback.configurationFile=" log-config)]))}
     :mvn/local-repo m2-cache-dir}))

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

(defn- repo-cache-location
  "Returns the location to use in the repo cache for the given build."
  [build]
  (str (cs/join "/" (take 2 (b/sid build))) blob/extension))

(defn restore-cache!
  "Restores the m2 cache for the specified repo.  This speeds up the child process, because
   it reduces the number of dependencies it needs to download (likely to zero)."
  [build {:keys [build-cache]}]
  (when build-cache
    (log/debug "Restoring build cache for build" (b/sid build))
    ;; Restore to parent because the dir is in the archive
    @(blob/restore build-cache (repo-cache-location build) (str (fs/parent m2-cache-dir)))))

(defn save-cache!
  "Saves the m2 cache generated by the child process to blob store"
  [build {:keys [build-cache]}]
  (when build-cache
    (log/debug "Saving build cache for build" (b/sid build))
    (try
      ;; This results in class not found error?
      ;; Something with running a sub process in oci container instances?
      ;; We could run the script in a second container instead, similar to oci container jobs.
      @(blob/save build-cache m2-cache-dir (repo-cache-location build))
      (catch Throwable ex
        (log/error "Failed to save build cache" ex)))))

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
    ;; TODO Validate script before spawning the child process?
    (restore-cache! build rt)
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
    (md/finally result #(save-cache! build rt))))

(defn generate-test-deps [rt watch?]
  (letfn [(test-lib-dir []
            (-> (utils/cwd) (fs/parent) (fs/path "test-lib") str))]
    {:aliases
     {:monkeyci/test
      {:extra-deps {'com.monkeyci/app (version-or rt utils/cwd)
                    'com.monkeyci/test (version-or rt test-lib-dir)}
       :paths ["."]
       :exec-fn 'kaocha.runner/exec-fn
       :exec-args (cond-> {:tests [{:type :kaocha.type/clojure.test
                                    :id :unit
                                    :ns-patterns ["-test$"]
                                    :source-paths ["."]
                                    :test-paths ["."]}]}
                    watch? (assoc :watch? true))}}}))

(defn test!
  "Executes any unit tests that have been defined for the build by starting a clojure process
   with a custom alias for running tests using kaocha."
  [build rt]
  (let [watch? (true? (get-in rt [:config :args :watch]))
        deps (generate-test-deps rt watch?)]
    (bp/process
     {:cmd ["clojure" "-Sdeps" (pr-str deps) "-X:monkeyci/test"]
      :out :inherit
      :err :inherit
      :dir (b/script-dir build)})))
