(ns monkey.ci.podman
  "Functions for running containers using Podman.  We don't use the api here, because
   it requires a socket, which is not always available.  Instead, we invoke the podman
   command as a child process and communicate with it using the standard i/o streams."
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [containers :as mcc]
             [context :as c]
             [utils :as u]]))

(defn- make-script-cmd [script]
  [(cs/join " && " script)])

(defmethod mcc/run-container :podman [{:keys [build-id step pipeline] :as ctx}]
  (log/info "Running build step " build-id "/" (:name step) "as podman container")
  (let [conf (mcc/ctx->container-config ctx)
        cn (or build-id "unkown-build")
        out-dir (doto (io/file (c/log-dir ctx)
                               build-id
                               (or (:name pipeline) (str (:index pipeline)))
                               (str (:index step)))
                  (.mkdirs))
        out-file (io/file out-dir "out.txt")
        err-file (io/file out-dir "err.txt")
        wd (u/step-work-dir ctx)
        cwd "/home/monkeyci"]
    (log/debug "Writing logs to" out-dir)
    @(bp/process {:dir wd
                  :out out-file
                  :err err-file
                  :cmd (concat
                        ["/usr/bin/podman" "run"
                         "-t" "--rm"
                         "--name" cn
                         "-v" (str wd ":" cwd ":z,u")
                         "-w" cwd
                         (:image conf)
                         ;; TODO Execute script step by step
                         "/bin/sh" "-ec"]
                        (make-script-cmd (:script step)))})))
