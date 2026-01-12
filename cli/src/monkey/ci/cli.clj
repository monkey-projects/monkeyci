(ns monkey.ci.cli
  (:require [cli-matic.core :as cc]
            [monkey.ci
             [commands :as cmd]
             [version :as v]]))

(def script-location-opt
  {:as "Script location"
   :option "dir"
   :short "d"
   :type :string
;;   :default b/default-script-dir
   :default ".monkeyci"
   })

(def run-build-cmd
  {:command "run"
   :description "Runs build locally"
   :opts [script-location-opt
          {:as "Git repository url"
           :option "git-url"
           :short "u"
           :type :string}
          {:as "Repository branch"
           :option "branch"
           :short "b"
           :type :string}
          {:as "Repository tag"
           :option "tag"
           :short "t"
           :type :string}
          {:as "Commit id"
           :option "commit-id"
           :type :string}
          {:as "Repository sid"
           :option "sid"
           :type :string}
          {:as "Build param"
           :option "param"
           :short "p"
           :type :string
           :multiple true}
          {:as "Build params file"
           :option "param-file"
           :type :string
           :multiple true}
          {:as "No output"
           :option "quiet"
           :short "q"
           :type :with-flag}
          {:as "Job filter"
           :option "filter"
           :short "f"
           :type :string
           :multiple true}]
   :runs cmd/run-build-local})

(def verify-build-cmd
  {:command "verify"
   :description "Verifies local build script"
   :opts [script-location-opt]
   :runs cmd/verify-build})

(def test-cmd
  {:command "test"
   :description "Runs build script unit tests"
   :opts [script-location-opt
          {:option "watch"
           :short "w"
           :type :with-flag}]
   :runs cmd/run-tests})

(def build-cmd
  {:command "build"
   :description "Build commands"
   :opts [{:as "API url"
           :option "api"
           :short "a"
           :type :string
           :env "MONKEYCI_API"}
          {:as "API key"
           :option "api-key"
           :short "k"
           :type :string
           :env "MONKEYCI_KEY"}
          {:as "Organization id"
           :option "org-id"
           :short "o"
           :type :string}
          {:as "Repository id"
           :option "repo-id"
           :short "r"
           :type :string}]
   :subcommands [run-build-cmd
                 verify-build-cmd
                 test-cmd]})

(def cli-config
  {:name "monkey-ci"
   :description "MonkeyCI: Powerful build pipeline runner"
   :version (v/version)
   :opts [{:as "Working directory"
           :option "workdir"
           :short "w"
           :type :string}
          {:as "Development mode"
           :option "dev-mode"
           :type :with-flag
           :default false}
          {:as "Configuration files"
           :option "config-file"
           :short "c"
           :type :string
           :multiple true}]
   :subcommands [build-cmd]})

(defn run! [args]
  (cc/run-cmd args cli-config))

(defn -main [& args]
  (run! args))
