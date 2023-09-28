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
             [utils :as u]]))

(defn- make-script-cmd [script]
  (->> script
       (cs/join " && ")
       (format "'%s'")))

(defmethod mcc/run-container :podman [{:keys [build-id step] :as ctx}]
  (log/info "Running build step " build-id "/" (:name step) "as podman container")
  (let [conf (mcc/ctx->container-config ctx)
        cn (or build-id "unkown-build")
        out-dir (doto (io/file (:work-dir ctx) build-id)
                  (.mkdirs))
        out-file (io/file out-dir "out.txt")
        err-file (io/file out-dir "err.txt")
        wd (u/step-work-dir ctx)
        cwd "/home/monkeyci"]
    @(bp/process {:dir wd
                  :out out-file
                  :err err-file
                  :cmd ["/usr/bin/podman" "run"
                        "-t" "--rm"
                        "--name" cn
;;                        "-e" (str "PS1=" build-id "$")
                        "-v" (str wd ":" cwd)
                        "-w" cwd
                        (:image conf)
                        ;; TODO Execute script step by step
                        "/bin/sh" "-ec" (make-script-cmd (:script step))]})))
