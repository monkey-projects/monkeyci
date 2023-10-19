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

(defn- make-cmd [{:keys [:container/cmd]}]
  (if (some? cmd)
    cmd
    ["/bin/sh" "-ec"]))

(defn- mounts [{:keys [:container/mounts]}]
  (mapcat (fn [[h c]]
            ;; TODO Mount options
            ["-v" (str h ":" c)])
          mounts))

(defn build-cmd-args
  "Builds command line args for the podman executable"
  [{:keys [build-id step] :as ctx}]
  (let [conf (mcc/ctx->container-config ctx)
        cn (or build-id "unkown-build")
        wd (c/step-work-dir ctx)
        cwd "/home/monkeyci"
        base-cmd ["/usr/bin/podman" "run"
                  "-t" "--rm"
                  "--name" cn
                  "-v" (str wd ":" cwd ":z")
                  "-w" cwd]]
    (concat
     ;; TODO Allow for more options to be passed in
     base-cmd
     (mounts step)
     [(:image conf)]
     (make-cmd step)
     ;; TODO Execute script step by step
     (make-script-cmd (:script step)))))

(defmethod mcc/run-container :podman [{:keys [build-id step pipeline] :as ctx}]
  (log/info "Running build step " build-id "/" (:name step) "as podman container")
  (let [out-dir (doto (io/file (c/log-dir ctx)
                               build-id
                               (or (:name pipeline) (str (:index pipeline)))
                               (str (:index step)))
                  (.mkdirs))
        [out-file err-file] (->> ["out.txt" "err.txt"]
                                 (map (partial io/file out-dir)))]
    (log/debug "Writing logs to" out-dir)
    @(bp/process {:dir (c/step-work-dir ctx)
                  :out out-file
                  :err err-file
                  :cmd (build-cmd-args ctx)})))
