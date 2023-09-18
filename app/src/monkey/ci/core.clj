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

(defn print-version [& _]
  (println (config/version)))

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
  "Wrap the command in a fn to enable better testing"
  [cmd {:keys [env] :as ctx}]
  (fn [args]
    (cmd (-> ctx
             (merge (config/build-config env args))
             (assoc :args args)))))

(def version-cmd
  {:command "version"
   :description "Prints current version"
   :runs print-version})

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
   :runs build})

(def server-cmd
  {:command "server"
   :description "Start MonkeyCI server"
   :opts [{:as "Listening port"
           :option "port"
           :short "p"
           :type :int
           :default 3000
           :env "PORT"}]
   :runs server})

(defn make-cli-config [{:keys [cmd-invoker] :or {cmd-invoker default-invoker} :as ctx}]
  (letfn [(invoker [cmd]
            (cmd-invoker cmd ctx))]
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
     :subcommands (->> [version-cmd
                        build-cmd
                        server-cmd]
                       ;; Wrap the run functions in the invoker
                       (mapv (fn [c] (update c :runs invoker))))}))

(defrecord CliConfig [env bus]
  sc/Lifecycle
  (start [this]
    (assoc this :cli-config (make-cli-config this)))

  (stop [this]
    (dissoc this :cli-config)))

(defn new-cli [env]
  (map->CliConfig {:env env}))

(defn run-command-async
  "Runs the command in an async fashion by sending an event, and waiting
   for the 'complete' event to arrive."
  [bus cmd]
  (-> bus 
      (e/post-event {:type :command/invoked
                     :command cmd})
      (e/wait-for :command/completed (filter (comp (partial = cmd) :command)))))

(defn make-system [config]
  (sc/system-map
   :bus (co/new-bus)
   :cli (sc/using (new-cli config) [:bus])))

(defn -main
  "Main entry point for the application."
  [& args]
  (let [sys (-> (make-system env)
                (sc/start))]
    (try
      (let [cli (get-in sys [:cli :cli-config])]
        (cli/run-cmd args cli))
      (finally
        (sc/stop sys)
        ;; Shutdown the agents otherwise the app will block for a while here
        (shutdown-agents)))))
