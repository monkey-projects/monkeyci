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
             [process :refer [version]]
             [runners :as r]]))

(defn make-config [env args]
  ;; TODO
  {:runner {:type (keyword (:monkeyci-runner-type env))}
   :env env
   :script args})

(defn print-version [_]
  (println version))

(defn build [env args]
  (println "Building")
  (log/debug "Arguments:" args)
  (let [ctx (make-config env args)
        runner (r/make-runner ctx)]
    (runner ctx)))

(defn server [args]
  (println "Starting server (TODO)")
  :todo)

(defn run-cmd [cmd args]
  (cmd args))

(defn cmd-invoker
  "Wrap the command in a fn to enable better testing"
  [cmd]
  (fn [args]
    (run-cmd cmd args)))

(defn cmd-env-invoker [cmd]
  (cmd-invoker (partial cmd env)))

(def cli-config
  {:name "monkey-ci"
   :description "MonkeyCI: Powerful build script runner"
   :version version
   :opts [{:as "Working directory"
           :option "workdir"
           :short "w"
           :type :string
           :default "."}]
   :subcommands [{:command "version"
                  :description "Prints current version"
                  :runs (cmd-invoker print-version)}
                 {:command "build"
                  :description "Runs build locally"
                  :opts [{:as "Script location"
                          :option "dir"
                          :short "d"
                          :type :string
                          :default ".monkeyci/"}]
                  :runs (cmd-env-invoker build)}
                 {:command "server"
                  :description "Start MonkeyCI server"
                  :runs (cmd-invoker server)}]})

(defn run-cli [args]
  (cli/run-cmd args cli-config))

(defn -main
  "Main entry point for the application."
  [& args]
  (try
    (run-cli args)
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
