(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka.process :as bp]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [config.core :as cc]
            [medley.core :as mc]
            [monkey.ci
             [config :refer [version] :as config]
             [events :as e]
             [script :as script]
             [utils :as utils]]
            [monkey.ci.build.core :as bc]
            [monkey.socket-async
             [core :as sa]
             [uds :as uds]]))

(defn run
  "Run function for when a build task is executed using clojure tools.  This function
   is run in a child process by the `execute!` function below.  This exits the VM
   with a nonzero value on failure."
  ([args env]
   (let [ctx (config/script-config env args)]
     (log/debug "Executing script with context" ctx)
     (when (bc/failed? (script/exec-script! ctx))
       (System/exit 1))))

  ([args]
   (run args cc/env)))

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
  [{:keys [script-dir] {:keys [dev-mode]} :args}]
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

(defn- build-args
  "Builds command-line args vector for script process"
  [config]
  (->> (select-keys config [:work-dir :script-dir :pipeline])
       (mc/remove-vals nil?)
       (mc/map-keys str)
       (mc/map-vals pr-str)
       (into [])
       (flatten)))

(defn- events-to-bus
  "Creates a listening socket (a uds) that will receive any events generated
   by the child script.  These events will then be sent to the bus."
  [{:keys [channel] :as bus}]
  (let [path (utils/tmp-file "events-" ".sock")
        listener (uds/listen-socket (uds/make-address path))
        inter-ch (ca/chan)]
    {:socket-path path
     :socket listener
     ;; Accept connection in separate thread.  We can change this later to a
     ;; virtual thread (Java 21+)
     :accept-thread (doto (Thread.
                           (fn []
                             (log/debug "Waiting for child process to connect")
                             (try 
                               (let [sock (uds/accept listener)]
                                 (log/debug "Incoming connection from child process accepted")
                                 ;; Set up a pipeline to avoid the bus closing
                                 (ca/pipe inter-ch channel false)
                                 (sa/read-onto-channel sock inter-ch))
                               (catch Exception ex
                                 (log/warn "No incoming connection from child could be accepted" ex)))))
                      (.start))}))

(defn execute!
  "Executes the build script located in given directory.  This actually runs the
   clojure cli with a generated `build` alias.  This expects absolute directories.
   Returns an object that can be `deref`ed to wait for the process to exit.  Will
   post a `build/completed` event on process exit."
  [{:keys [work-dir script-dir bus] :as ctx}]
  (log/info "Executing process in" work-dir)
  (let [{:keys [socket-path socket]} (when bus
                                       (events-to-bus bus))]
    (bp/process
     {:dir script-dir
      :out :inherit
      :err :inherit
      :cmd (-> ["clojure"
                "-Sdeps" (generate-deps ctx)
                "-X:monkeyci/build"]
               (concat (build-args ctx))
               (vec))
      :extra-env {:monkeyci-event-socket socket-path}
      :exit-fn (fn [{:keys [process] :as p}]
                 (let [exit (or (some-> process (.exitValue)) 0)]
                   (log/debug "Script process exited with code" exit ", cleaning up")
                   (when socket-path 
                     (uds/close socket)
                     (uds/delete-address socket-path))
                   ;; FIXME When there is no bus, the complete event cannot be sent
                   ;; and the command will never finish.
                   (when bus
                     (log/debug "Posting build/completed event")
                     (e/post-event bus {:type :build/completed
                                        :exit exit
                                        :result (if (zero? exit) :success :error)
                                        :process p}))))})))
