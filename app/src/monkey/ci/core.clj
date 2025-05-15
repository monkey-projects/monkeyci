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
             [cli :as mcli]
             [config :as config]
             [version :as v]]
            [monkey.ci.logging.logback :as ll]))

(defn system-invoker
  "Creates a new runtime and invokes the command using the specified application 
   mode.  By default it uses the base system, but you can specify your own for 
   testing purposes."
  [{:keys [command app-mode runtime?] :as cmd :or {runtime? true}} env]
  (fn [args]
    (try
      (log/debug "Invoking command with arguments:" args)
      (let [config (config/app-config env args)]
        (log/info "Executing command:" command)
        (command config))
      (catch Throwable t
        ;; Explicitly catch errors because cli-matic exits the vm, so we need
        ;; to log the error here.
        (log/error "Failed to run command" t)
        1))))

(defn make-cli-config [{:keys [cmd-invoker env] :or {cmd-invoker system-invoker}}]
  (letfn [(invoker [cmd]
            (cmd-invoker cmd env))]
    ;; Wrap the run functions in the invoker
    (mcli/set-invoker mcli/base-config invoker)))

(defn -main
  "Main entry point for the application."
  [& args]
  ;; If the logback config is provided in an env var, load it first
  (ll/configure-from-env "MONKEYCI_LOGBACK")
  (try
    (log/info "Starting MonkeyCI version" (v/version))
    (cli/run-cmd args (make-cli-config {:env env}))
    (catch Throwable ex
      (log/error "Failed to run application" ex)
      1)
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
