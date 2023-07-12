(ns monkey.ci.core
  "Core namespace for the Monkey CI app.  This contains the entrypoint which
   processes the configuration.  This configuration determines whether the
   application runs as a server, execute a single script, which type of runner
   is enabled, etc..."
  (:gen-class)
  (:require [cli-matic.core :as cli]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [monkey.ci.runners :as r]))

(def version "0.1.0-SNAPSHOT")

(defn make-config [env args]
  ;; TODO
  {:runner {:type (keyword (:monkeyci-runner-type env))}
   :env env
   :script args})

(defn print-version [_]
  (println "MonkeyCI version:" version))

(defn build [env args]
  (println "Building")
  (log/debug "Arguments:" args)
  (let [ctx (make-config env args)
        runner (r/make-runner ctx)]
    (runner ctx)))

(defn server [args]
  (println "Starting server")
  :todo)

(def cli-config
  {:name "monkey-ci"
   :description "MonkeyCI: Powerful build script runner"
   :version version
   :subcommands [{:command "version"
                  :description "Prints current version"
                  :runs print-version}
                 {:command "build"
                  :description "Runs build locally"
                  :opts [{:as "Script location"
                          :option "dir"
                          :short "d"
                          :type :string
                          :default ".monkeyci/"}]
                  :runs (partial build env)}
                 {:command "server"
                  :description "Start MonkeyCI server"
                  :runs server}]})

(defn -main
  "Main entry point for the application."
  [& args]
  (try
    (cli/run-cmd args cli-config)
    (finally
      ;; Shutdown the agents otherwise the app will block for a while here
      (shutdown-agents))))
