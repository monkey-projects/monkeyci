(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [config :refer [version]]
             [script :as script]
             [utils :as utils]]
            [monkey.ci.build.core :as bc]))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exits the VM
   with a nonzero value on failure."
  [args]
  (let [ctx (assoc args :container-runner :docker)] ; Force docker container runner for now
    (log/debug "Executing script with context" ctx)
    (when (bc/failed? (script/exec-script! ctx))
      (System/exit 1))))

(defn- local-script-lib-dir
  "Calculates the local script directory as a sibling dir of the
   current (app) directory."
  []
  (-> (.. (io/file (utils/cwd))
          (getAbsoluteFile)
          (getParentFile))
      (io/file "lib")
      (str)))

(defn- generate-deps
  "Generates a string that will be added as a commandline argument
   to the clojure process when running the script.  Any existing `deps.edn`
   should be used as well."
  [{:keys [dev-mode script-dir]}]
  (pr-str {:paths [script-dir]
           :aliases
           {:monkeyci/build
            {:exec-fn 'monkey.ci.process/run
             :extra-deps {'com.monkeyci/script
                          (if dev-mode
                            {:local/root (local-script-lib-dir)}
                            {:mvn/version (version)})
                          'com.monkeyci/app
                          (if dev-mode
                            {:local/root (utils/cwd)}
                            {:mvn/version (version)})}
             ;; TODO Only add this if the file actually exists
             :jvm-opts [(str "-Dlogback.configurationFile=" (io/file script-dir "logback.xml"))]}}}))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias.  This expects absolute directories."
  [{:keys [work-dir script-dir] :as config}]
  (log/info "Executing process in" work-dir)
  (try 
    ;; Run the clojure cli with the "build" alias. Add some parameters to the script
    ;; in the form of edn.
    (let [args (->> (select-keys config [:work-dir :script-dir :pipeline])
                    (mc/remove-vals nil?)
                    (mc/map-keys str)
                    (mc/map-vals pr-str)
                    (into [])
                    (flatten))
          ;; TODO Pass additional config through env
          result (apply bp/shell
                        {:dir script-dir
                         ;; TODO Stream output or write to file
                         :out :inherit
                         :err :inherit
                         :continue true}
                        "clojure"
                        "-Sdeps" (generate-deps config)
                        "-X:monkeyci/build"
                        args)]
      (log/info "Script executed with exit code" (:exit result))
      ;;(log/debug "Output:" (:out result))
      ;; If the process has a nonzero exit code, print the stderr output
      #_(when-not (zero? (:exit result))
        (log/warn "Error output:" (:err result)))
      #_(bp/shell {:dir script-dir} "clojure" "-Stree" "-Sdeps" (generate-deps config) "-A:monkeyci/build")
      result)
    (catch Exception ex
      (let [{:keys [out err] :as data} (ex-data ex)]
        (log/error "Failed to execute build script")
        ;;(log/error "Output:" out)
        ;;(log/error "Error:" err)
        (throw ex)))))
