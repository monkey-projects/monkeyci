(ns monkey.ci.cli
  (:require [clojure.walk :as w]
            [monkey.ci
             [build :as b]
             [commands :as cmd]
             [version :as v]]))

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
           :type :string}]
   :runs {:command cmd/run-build-local
          :app-mode :cli}})

(def verify-build-cmd
  {:command "verify"
   :description "Verifies local build script"
   :opts [script-location-opt]
   :runs {:command cmd/verify-build
          :app-mode :cli}})

;; (def list-build-cmd
;;   {:command "list"
;;    :description "Lists builds for customer or repo"
;;    :runs {:command cmd/list-builds
;;           :app-mode :cli}})

;; (def watch-cmd
;;   {:command "watch"
;;    :description "Logs build events for customer or repo"
;;    :runs {:command cmd/watch
;;           :app-mode :cli}})

(def test-cmd
  {:command "test"
   :description "Runs build script unit tests"
   :opts [script-location-opt
          {:option "watch"
           :short "w"
           :type :with-flag}]
   :runs {:command cmd/run-tests
          :app-mode :cli}})

(def build-cmd
  {:command "build"
   :description "Build commands"
   ;; :opts [{:option "server"
   ;;         :short "s"
   ;;         :as "Server URL"
   ;;         :type :string}
   ;;        {:as "Customer id"
   ;;         :option "org-id"
   ;;         :short "c"
   ;;         :type :string}
   ;;        {:as "Repository id"
   ;;         :option "repo-id"
   ;;         :short "r"
   ;;         :type :string}]
   :subcommands [run-build-cmd
                 verify-build-cmd
                 ;; Disabled until refactored
                 #_list-build-cmd
                 #_watch-cmd
                 test-cmd]})

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
          :app-mode :server}})

(def sidecar-cmd
  {:command "sidecar"
   :description "Run as sidecar (for internal use)"
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

(def controller-cmd
  {:command "controller"
   :description "Runs as controller (for internal use)"
   :runs {:command cmd/controller
          :app-mode :script}})

(def internal-cmd
  {:command "internal"
   :description "Commands for internal use"
   :subcommands [server-cmd
                 controller-cmd
                 sidecar-cmd]})

(def issue-creds-cmd
  {:command "issue"
   :description "Issue credits"
   :opts [{:as "Customer id"
           :option "customer"
           :short "c"
           :type :string}
          {:as "Issue for all"
           :option "all"
           :type :with-flag}
          {:as "Issue for date"
           :option "date"
           :short "d"
           :type :yyyy-mm-dd}]
   :runs {:command cmd/issue-creds
          :app-mode :cli}})

(def reaper-cmd
  {:command "reaper"
   :description "Cancel dangling builds"
   :runs {:command cmd/cancel-dangling-builds}})

(def admin-cmd
  {:command "admin"
   :description "Administrative actions"
   :opts [{:as "Username"
           :option "username"
           :short "u"
           :type :string
           :env "MONKEYCI_USERNAME"
           :default :present}
          {:as "Private key file"
           :option "private-key"
           :short "k"
           :type :string
           :env "MONKEYCI_PRIVATE_KEY"
           :default :present}
          {:as "API url"
           :option "api"
           :short "a"
           :type :string
           :env "MONKEYCI_API"
           :default "https://api.monkeyci.com/v1"}]
   :subcommands [issue-creds-cmd
                 reaper-cmd]})

(def base-config
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
   :subcommands [build-cmd
                 internal-cmd
                 admin-cmd]})

(defn set-invoker
  "Updates the cli config to replace the `runs` config with the given invoker."
  [conf inv]
  (w/prewalk
   (fn [x]
     (if (and (map-entry? x) (= :runs (first x)))
       [:runs (inv (second x))]
       x))
   conf))
