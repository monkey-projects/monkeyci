-(ns monkey.ci.core
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
             [listeners :as l]
             [logging]
             [reporting]
             [runtime :as rt]
             [runners]
             [sidecar]
             [utils :as u]
             [workspace]]
            [monkey.ci.containers
             [oci]]
            [monkey.ci.events
             [core :as ec]
             [manifold]]
            [monkey.ci.storage
             [cached]
             [file]
             [oci]]))

(defn register-listeners [runtime]
  (ec/add-listener (get-in runtime [:events :receiver])
                   (l/build-update-handler runtime)))

(defn system-invoker
  "The event invoker starts a subsystem according to the command requirements,
   and posts the `command/invoked` event.  This event should be picked up by a
   handler in the system.  When the command is complete, it should post a
   `command/completed` event for the same command.  By default it uses the base
   system, but you can specify your own for testing purposes."
  [{:keys [command app-mode] :as cmd} env]
  (fn [args]
    (log/debug "Invoking command with arguments:" args)
    (let [config (config/app-config env args)]
      ;; When app mode is specified, pass the runtime for new-style invocations
      (rt/with-runtime config app-mode runtime
        ;; TODO Make more generic
        (register-listeners runtime)
        (log/info "Executing command:" command)
        (command runtime)))))

(defn make-cli-config [{:keys [cmd-invoker env] :or {cmd-invoker system-invoker}}]
  (letfn [(invoker [cmd]
            (cmd-invoker cmd env))]
    ;; Wrap the run functions in the invoker
    (mcli/set-invoker mcli/base-config invoker)))

(defn -main
  "Main entry point for the application."
  [& args]
  (try
    (log/info "Starting MonkeyCI version" (config/version))
    (cli/run-cmd args (make-cli-config {:env env}))
    (catch Exception ex
      (log/error "Failed to run application" ex)
      1)
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
