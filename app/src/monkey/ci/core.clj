(ns monkey.ci.core
  "Core namespace for the Monkey CI app.  This contains the entrypoint which
   processes the configuration.  This configuration determines whether the
   application runs as a server, execute a single script, which type of runner
   is enabled, etc..."
  (:gen-class)
  (:require [cli-matic.core :as cli]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as sc]
            [config.core :refer [env]]
            [monkey.ci
             [cli :as mcli]
             [components :as co]
             [config :as config]
             [utils :as u]]))

;; The base system components.  Depending on the command that's being
;; executed, a subsystem will be created and initialized.
(def base-system
  (sc/system-map
   :bus (co/new-bus)
   :context (-> (co/new-context nil)
                (sc/using {:event-bus :bus
                           :config :config
                           :storage :storage}))
   :http (-> (co/new-http-server)
             (sc/using [:context :listeners]))
   :listeners (-> (co/new-listeners)
                  (sc/using [:bus :context]))
   :storage (-> (co/new-storage)
                (sc/using [:config]))))

(def always-required-components [:bus :context])

(defn system-invoker
  "The event invoker starts a subsystem according to the command requirements,
   and posts the `command/invoked` event.  This event should be picked up by a
   handler in the system.  When the command is complete, it should post a
   `command/completed` event for the same command.  By default it uses the base
   system, but you can specify your own for testing purposes."
  ([{:keys [command requires]} env base-system]
   (fn [args]
     (log/debug "Invoking command with arguments:" args)
     (let [config (config/app-config env args)
           {:keys [bus] :as sys} (-> base-system
                                     (assoc :config config)
                                     (sc/subsystem (concat requires always-required-components))
                                     (sc/start-system))
           ctx (:context sys)]
       ;; Register shutdown hook to stop the system
       (u/add-shutdown-hook! #(sc/stop-system sys))
       ;; Run the command with the context.  If this returns a channel, then
       ;; cli-matic will wait until it closes.  If this returns a number, will use
       ;; it as the process exit code.
       (command (assoc ctx :system sys)))))
  ([cmd env]
   (system-invoker cmd env base-system)))

(defn make-cli-config [{:keys [cmd-invoker env] :or {cmd-invoker system-invoker}}]
  (letfn [(invoker [cmd]
            (cmd-invoker cmd env))]
    ;; Wrap the run functions in the invoker
    (update mcli/base-config :subcommands (partial mapv (fn [c] (update c :runs invoker))))))

(defn -main
  "Main entry point for the application."
  [& args]
  (try
    (cli/run-cmd args (make-cli-config {:env env}))
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
