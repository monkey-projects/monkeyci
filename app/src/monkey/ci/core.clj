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
             [components :as co]
             [config :as config]
             [events :as e]
             [runners :as r]]
            [monkey.ci.web.handler :as web]))

(def base-system
  (sc/system-map
   :bus (co/new-bus)))

(defn build [ctx]
  (println "Building")
  (let [runner (r/make-runner ctx)
        {:keys [result exit] :as output} (runner ctx)]
    (condp = (or result :unknown)
      :success (log/info "Success!")
      :warning (log/warn "Exited with warnings.")
      :error   (log/error "Failure.")
      :unknown (log/warn "Unknown result."))
    ;; Return exit code, this will be the process exit code as well
    exit))

(defn server [ctx]
  (println "Starting HTTP server")
  (-> (web/start-server ctx)
      (web/wait-until-stopped)))

(defn default-invoker
  "The default invoker starts a subsystem according to the command requirements,
   and posts the `command/invoked` event.  This event should be picked up by a
   handler in the system.  When the command is complete, it should post a
   `command/completed` event for the same command.  By default it uses the base
   system, but you can specify your own for testing purposes."
  ([{:keys [command requires]} env base-system]
   (fn [args]
     (let [{:keys [bus] :as sys} (-> (sc/subsystem base-system requires)
                                     (sc/start-system))]
       (if (some? bus)
         (do
           (e/post-event bus {:type :command/invoked
                              :command command
                              :env env
                              :args args})
           (e/wait-for bus :command/completed (filter (comp (partial = command) :command))))
         (log/warn "Unable to invoke command, event bus has not been configured.")))))
  ([cmd env]
   (default-invoker cmd env base-system)))

(def build-cmd
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
   :runs {:command :build
          :requires [:bus]}})

(def server-cmd
  {:command "server"
   :description "Start MonkeyCI server"
   :opts [{:as "Listening port"
           :option "port"
           :short "p"
           :type :int
           :default 3000
           :env "PORT"}]
   :runs {:command :http
          :requires [:bus]}})

(def base-config
  {:name "monkey-ci"
   :description "MonkeyCI: Powerful build pipeline runner"
   :version (config/version)
   :opts [{:as "Working directory"
           :option "workdir"
           :short "w"
           :type :string
           :default "."}
          {:as "Development mode"
           :option "dev-mode"
           :type :with-flag
           :default false}]
   :subcommands [build-cmd
                 server-cmd]})

(defn make-cli-config [{:keys [cmd-invoker env] :or {cmd-invoker default-invoker}}]
  (letfn [(invoker [cmd]
            (cmd-invoker cmd env))]
    ;; Wrap the run functions in the invoker
    (update base-config :subcommands (partial mapv (fn [c] (update c :runs invoker))))))

(defn -main
  "Main entry point for the application."
  [& args]
  (try
    (cli/run-cmd args (make-cli-config env))
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
