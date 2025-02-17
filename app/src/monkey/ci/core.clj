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
             [artifacts]
             [cache]
             [cli :as mcli]
             [config :as config]
             [containers]
             [git]
             [listeners]
             [logging]
             [runtime :as rt]
             [runners]
             [sidecar]
             [utils :as u]
             [version :as v]
             [workspace]]
            [monkey.ci.containers
             [oci]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.reporting.print]
            [monkey.ci.runners
             [server]]
            [monkey.ci.storage
             [cached]
             [file]
             [oci]
             [sql]]))

(defn system-invoker
  "Creates a new runtime and invokes the command using the specified application 
   mode.  By default it uses the base system, but you can specify your own for 
   testing purposes."
  [{:keys [command app-mode runtime?] :as cmd :or {runtime? true}} env]
  (fn [args]
    (log/debug "Invoking command with arguments:" args)
    (let [config (config/app-config env args)]
      (log/info "Executing command:" command)
      (if runtime?
        (rt/with-runtime config app-mode runtime
          (command runtime))
        (command config)))))

(defn make-cli-config [{:keys [cmd-invoker env] :or {cmd-invoker system-invoker}}]
  (letfn [(invoker [cmd]
            (cmd-invoker cmd env))]
    ;; Wrap the run functions in the invoker
    (mcli/set-invoker mcli/base-config invoker)))

(defn -main
  "Main entry point for the application."
  [& args]
  (try
    (log/info "Starting MonkeyCI version" (v/version))
    (cli/run-cmd args (make-cli-config {:env env}))
    (catch Throwable ex
      (log/error "Failed to run application" ex)
      1)
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
