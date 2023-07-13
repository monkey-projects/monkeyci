(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci.script :as script]
            [monkey.ci.build.core :as bc]))

(def version "0.1.0-SNAPSHOT")

(defn- cwd []
  (System/getProperty "user.dir"))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below."
  [args]
  (log/debug "Executing script with args" args)
  (when (bc/failed? (script/exec-script! (cwd)))
    (System/exit 1)))

(defn- pwd []
  (System/getProperty "user.dir"))

(defn- generate-deps
  "Generates a string that will be added as a commandline argument
   to the clojure process when running the script.  Any existing `deps.edn`
   should be used as well."
  []
  (cs/join " " ["{:paths [\".\"]"
                ":aliases {:monkeyci/build {:exec-fn monkey.ci.process/run"
                ;; Include both the script and app libs
                (format (str ":extra-deps {com.monkeyci/script {:mvn/version \"%s\"} "
                             "com.monkeyci/app {:local/root \"%s\"}}")
                        version
                        (pwd))
                "}}}"]))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias."
  [dir]
  (log/info "Executing process in" dir)
  (try 
    ;; Run the clojure cli with the "build" alias
    (let [result (bp/shell {:dir dir
                            :out :string
                            :err :string}
                           "clojure"
                           "-Sdeps" (generate-deps)
                           "-X:monkeyci/build"
                           ":workdir" (str "\"" dir "\""))]
      (log/info "Script executed with exit code" (:exit result))
      result)
    (catch Exception ex
      (log/error "Failed to execute build script" ex)
      (throw ex))))
