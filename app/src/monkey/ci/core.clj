(ns monkey.ci.core
  "Core namespace for the Monkey CI app.  This contains the entrypoint which
   processes the configuration.  This configuration determines whether the
   application runs as a server, execute a single script, which type of runner
   is enabled, etc..."
  (:gen-class)
  (:require [cli-matic.core :as cli]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [monkey.ci
             [process :as p]
             [runners :as r]]))

(defn make-config
  "Creates a build configuration that includes the environment and any args passed in.
   This is then used to create a build runner."
  [env args]
  {:runner {:type (keyword (:monkeyci-runner-type env))}
   :env env
   :script args})

(defn print-version [& _]
  (println (p/version)))

(defn build [env args]
  (println "Building")
  (log/debug "Arguments:" args)
  (let [ctx (make-config env args)
        runner (r/make-runner ctx)
        {:keys [result exit] :as output} (runner ctx)]
    (condp = (or result :unknown)
      :success (log/info "Success!")
      :warning (log/warn "Exited with warnings.")
      :error   (log/error "Failure.")
      :unknown (log/warn "Unknown result."))
    ;; Return exit code, this will be the process exit code as well
    exit))

(defn server [& _]
  (println "Starting server (TODO)")
  :todo)

(defn default-invoker
  "Wrap the command in a fn to enable better testing"
  [cmd env]
  (fn [args]
    (cmd env args)))

(defn make-cli-config [{:keys [env cmd-invoker] :or {cmd-invoker default-invoker}}]
  (letfn [(invoker [cmd]
            (cmd-invoker cmd env))]
    {:name "monkey-ci"
     :description "MonkeyCI: Powerful build script runner"
     :version (p/version)
     :opts [{:as "Working directory"
             :option "workdir"
             :short "w"
             :type :string
             :default "."}]
     :subcommands [{:command "version"
                    :description "Prints current version"
                    :runs (invoker print-version)}
                   {:command "build"
                    :description "Runs build locally"
                    :opts [{:as "Script location"
                            :option "dir"
                            :short "d"
                            :type :string
                            :default ".monkeyci/"}
                           {:as "Pipeline name"
                            :option "pipeline"
                            :short "p"
                            :type :string}]
                    :runs (invoker build)}
                   {:command "server"
                    :description "Start MonkeyCI server"
                    :runs (invoker server)}]}))

(defn run-cli
  ([config args]
   (cli/run-cmd args config))
  ([args]
   (run-cli (make-cli-config {:env env}) args)))

(defn -main
  "Main entry point for the application."
  [& args]
  (try
    (run-cli args)
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
