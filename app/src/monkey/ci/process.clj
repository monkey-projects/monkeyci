(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [script :as script]
             [utils :as utils]]
            [monkey.ci.build.core :as bc]))

(def version (or (System/getenv "MONKEYCI_VERSION") "0.1.0-SNAPSHOT"))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exists the VM
   with a nonzero value on failure."
  [args]
  (log/debug "Executing script with args" args)
  (when (bc/failed? (script/exec-script! args))
    (System/exit 1)))

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
                            {:mvn/version version})
                          'com.monkeyci/app
                          (if dev-mode
                            {:local/root (utils/cwd)}
                            {:mvn/version version})}
             ;; TODO Only add this if the file actually exists
             :jvm-opts [(str "-Dlogback.configurationFile=" (io/file script-dir "logback.xml"))]}}}))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias."
  [{:keys [work-dir script-dir] :as config}]
  (log/info "Executing process in" work-dir)
  (try 
    ;; Run the clojure cli with the "build" alias
    (let [result (bp/shell {:dir work-dir
                            ;; TODO Stream output or write to file
                            :out :string
                            :err :string
                            :continue true}
                           "clojure"
                           "-Sdeps" (generate-deps config)
                           "-X:monkeyci/build"
                           ":work-dir" (pr-str work-dir)
                           ":script-dir" (pr-str script-dir))]
      (log/info "Script executed with exit code" (:exit result))
      (log/debug "Output:" (:out result))
      (when-not (zero? (:exit result))
        (log/warn "Error output:" (:err result)))
      result)
    (catch Exception ex
      (let [{:keys [out err] :as data} (ex-data ex)]
        (log/error "Failed to execute build script")
        (log/error "Output:" out)
        (log/error "Error:" err)
        (if (number? (:exit data))
          ;; Return process error with exit code
          data
          ;; It's some other kind of error
          (throw ex))))))
