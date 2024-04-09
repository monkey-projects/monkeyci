(ns monkey.ci.cli
  (:require [clojure.walk :as w]
            [monkey.ci
             [build :as b]
             [commands :as cmd]
             [config :as config]]))

(def script-location-opt
  {:as "Script location"
   :option "dir"
   :short "d"
   :type :string
   :default b/default-script-dir})

(def run-build-cmd
  {:command "run"
   :description "Runs build locally"
   :opts [script-location-opt
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
           :type :string}
          {:as "Repository sid"
           :option "sid"
           :type :string}]
   :runs {:command cmd/run-build
          :app-mode :cli}})

(def verify-build-cmd
  {:command "verify"
   :description "Verifies local build script"
   :opts [script-location-opt]
   :runs {:command cmd/verify-build
          :app-mode :cli}})

(def list-build-cmd
  {:command "list"
   :description "Lists builds for customer or repo"
   :runs {:command cmd/list-builds
          :app-mode :cli}})

(def watch-cmd
  {:command "watch"
   :description "Logs build events for customer or repo"
   :runs {:command cmd/watch
          :app-mode :cli}})

(def build-cmd
  {:command "build"
   :description "Build commands"
   :opts [{:option "server"
           :short "s"
           :as "Server URL"
           :type :string}
          {:as "Customer id"
           :option "customer-id"
           :short "c"
           :type :string}
          {:as "Repository id"
           :option "repo-id"
           :short "r"
           :type :string}]
   :subcommands [run-build-cmd
                 verify-build-cmd
                 list-build-cmd
                 watch-cmd]})

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
          :requires [:http]
          :app-mode :server}})

(def sidecar-cmd
  {:command "sidecar"
   :description "Run as sidecar"
   :opts [{:as "Events file"
           :option "events-file"
           :short "e"
           :type :string
           :default "events"}
          {:as "Start file"
           :option "start-file"
           :short "s"
           :type :string
           :default "start"}
          {:as "Abort file"
           :option "abort-file"
           :short "a"
           :type :string
           :default "abort"}
          {:as "Job config"
           :option "job-config"
           :short "t"
           :type :ednfile}]
   :runs {:command cmd/sidecar
          :app-mode :script}})

(def base-config
  {:name "monkey-ci"
   :description "MonkeyCI: Powerful build pipeline runner"
   :version (config/version)
   :opts [{:as "Working directory"
           :option "workdir"
           :short "w"
           :type :string}
          {:as "Development mode"
           :option "dev-mode"
           :type :with-flag
           :default false}
          {:as "Configuration file"
           :option "config-file"
           :short "c"
           :type :string
           :multiple true}]
   :subcommands [build-cmd
                 server-cmd
                 sidecar-cmd]})

(defn set-invoker
  "Updates the cli config to replace the `runs` config with the given invoker."
  [conf inv]
  (w/prewalk
   (fn [x]
     (if (and (map-entry? x) (= :runs (first x)))
       [:runs (inv (second x))]
       x))
   conf))
