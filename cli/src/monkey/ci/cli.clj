(ns monkey.ci.cli
  (:gen-class)
  (:require [babashka.fs :as fs]
            [cli-matic.core :as cli]
            [monkey.ci.cli
             [build :as b]
             [print :as p]
             [test :as t]
             [utils :as u]
             [verify :as verify]
             [version :as v]]))

(defn print-version [_]
  (p/print-version v/version))

(defn verify [{:keys [dir]}]
  (let [path (u/find-script-dir dir)]
    (println "Verifying directory:" path)
    (let [r (verify/verify path)]
      (doseq [{:keys [details]} r]
        (p/print-findings (:findings details))))))

(def dir-opt
  {:as "Project directory"
   :option "dir"
   :short "d"
   :type :string
   :default "."})

(def watch-opt
  {:as "Watch"
   :option "watch"
   :short "w"
   :type :with-flag})

(def config
  {:command "monkey-ci"
   :description "Clojure-powered CI/CD tool"
   :version v/version
   :subcommands
   [{:command "version"
     :description "Prints current version"
     :runs #'print-version}
    {:command "verify"
     :description "Verifies the syntax of the build script files"
     :runs #'verify
     :opts [dir-opt]}
    {:command "test"
     :description "Run unit tests in the specified directory"
     :runs #'t/run-tests
     :opts [dir-opt
            watch-opt]}
    {:command "build"
     :description "Run a local build of the MonkeyCI script in the given directory"
     :runs #'b/build
     :opts [dir-opt]}]})

(defn -main [& args]
  (cli/run-cmd args config))
