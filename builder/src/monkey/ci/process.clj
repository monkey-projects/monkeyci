(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci.script :as script]
            [monkey.ci.build.core :as bc]))

(defn- cwd []
  (System/getProperty "user.dir"))

(defn run
  "Run function for when a build task is executed using clojure tools"
  [args]
  (log/info "Executing script with args" args)
  (when (bc/failed? (script/exec-script! (cwd)))
    (System/exit 1)))

(defn- generate-deps
  "Generates a string that will be added as a commandline argument
   to the clojure process when running the script.  Any existing `deps.edn`
   should be used as well."
  []
  (cs/join " " ["{:paths [\".\"]"
                ":aliases {:build {:exec-fn monkey.ci.process/run"
                (format ":extra-deps {monkey.ci/build {:local/root \"%s\"}}" (cwd))
                "}}}"]))

(defn execute!
  "Executes the build script located in given directory."
  [dir]
  (log/info "Executing process in" dir)
  ;; Run the clojure cli with the "build" command
  (bp/shell {:dir dir
             :out :string}
            "clojure" "-Sdeps" (generate-deps) "-X:build" ":workdir" (str "\"" dir "\"")))
