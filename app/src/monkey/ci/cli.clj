(ns monkey.ci.cli
  (:require [monkey.ci
             [commands :as cmd]
             [config :as config]
             [runners :as r]]))

(def build-cmd
  {:command "build"
   :description "Runs build locally"
   :opts [{:as "Script location"
           :option "dir"
           :short "d"
           :type :string
           :default r/default-script-dir}
          {:as "Pipeline name"
           :option "pipeline"
           :short "p"
           :type :string}
          {:as "Git repository url"
           :option "git-url"
           :short "u"
           :type :string}
          {:as "Repository branch"
           :option "branch"
           :short "b"
           :type :string}
          {:as "Commit id"
           :option "commit-id"
           :type :string}]
   :runs {:command cmd/build}})

(def server-cmd
  {:command "server"
   :description "Start MonkeyCI server"
   :opts [{:as "Listening port"
           :option "port"
           :short "p"
           :type :int
           :default 3000
           :env "PORT"}]
   :runs {:command cmd/http-server
          :requires [:http]}})

(def watch-cmd
  {:command "watch"
   :description "Logs events for customer, project or repo"
   :opts [{:as "Server url"
           :option "url"
           :short "u"
           :type :string
           :spec :conf/url}
          {:as "Customer id"
           :option "customer-id"
           :short "c"
           :type :string}
          {:as "Project id"
           :option "project-id"
           :short "p"
           :type :string}
          {:as "Repository id"
           :option "repo-id"
           :short "r"
           :type :string}]
   :runs {:command cmd/watch}})

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
           :default false}
          {:as "Configuration file"
           :option "config-file"
           :short "c"
           :type :string}]
   :subcommands [build-cmd
                 server-cmd
                 watch-cmd]})
