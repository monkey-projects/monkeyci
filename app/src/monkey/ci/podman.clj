(ns monkey.ci.podman
  "Functions for running containers using Podman.  We don't use the api here, because
   it requires a socket, which is not always available.  Instead, we invoke the podman
   command as a child process and communicate with it using the standard i/o streams."
  (:require [babashka.process :as bp]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [containers :as mcc]
             [context :as c]
             [logging :as l]
             [utils :as u]]))

(defn- make-script-cmd [script]
  [(cs/join " && " script)])

(defn- make-cmd [{:keys [:container/cmd]}]
  (if (some? cmd)
    cmd
    ;; When no command is given, use /bin/sh as entrypoint and fail on errors
    ["-ec"]))

(defn- mounts [{:keys [:container/mounts]}]
  (mapcat (fn [[h c]]
            ;; TODO Mount options
            ["-v" (str h ":" c)])
          mounts))

(defn- env-vars [{:keys [:container/env]}]
  (mapcat (fn [[k v]]
            ["-e" (str k "=" v)])
          env))

(defn- entrypoint [{ep :container/entrypoint cmd :container/cmd}]
  (cond
    ep
    ["--entrypoint" (str "'" (json/generate-string ep) "'")]
    (nil? cmd)
    ["--entrypoint" "/bin/sh"]))

(defn build-cmd-args
  "Builds command line args for the podman executable"
  [{:keys [build-id step] :as ctx}]
  (let [conf (mcc/ctx->container-config ctx)
        cn (or build-id "unkown-build")
        wd (c/step-work-dir ctx)
        cwd "/home/monkeyci"
        base-cmd ["/usr/bin/podman" "run"
                  "-t" "--rm"
                  "--privileged"       ; TODO Find a way to avoid this
                  "--name" cn
                  "-v" (str wd ":" cwd ":z")
                  "-w" cwd]]
    (concat
     ;; TODO Allow for more options to be passed in
     base-cmd
     (mounts step)
     (env-vars step)
     (entrypoint step)
     [(:image conf)]
     (make-cmd step)
     ;; TODO Execute script step by step
     (make-script-cmd (:script step)))))

(defmethod mcc/run-container :podman [{:keys [build-id step pipeline] :as ctx}]
  (log/info "Running build step " build-id "/" (:name step) "as podman container")
  (let [log-maker (c/log-maker ctx)
        log-base [build-id
                  (or (:name pipeline) (str (:index pipeline)))
                  (str (:index step))]
        [out-log err-log :as loggers] (->> ["out.txt" "err.txt"]
                                           (map (partial conj log-base))
                                           (map (partial log-maker ctx)))]
    (log/debug "Log base is:" log-base)
    (-> (bp/process {:dir (c/step-work-dir ctx)
                     :out (l/log-output out-log)
                     :err (l/log-output err-log)
                     :cmd (build-cmd-args ctx)})
        (l/handle-process-streams loggers)
        (deref))))
