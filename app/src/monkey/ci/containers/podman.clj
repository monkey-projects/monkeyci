(ns monkey.ci.containers.podman
  "Functions for running containers using Podman.  We don't use the api here, because
   it requires a socket, which is not always available.  Instead, we invoke the podman
   command as a child process and communicate with it using the standard i/o streams."
  (:require [babashka.process :as bp]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [artifacts :as art]
             [build :as b]
             [cache :as cache]
             [containers :as mcc]
             [logging :as l]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.build.core :as bc]))

(defn- make-script-cmd [script]
  [(cs/join " && " script)])

(defn- make-cmd [conf]
  (if-let [cmd (mcc/cmd conf)]
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

(defn- platform [rt]
  (when-let [p (or (get-in rt [:job :container/platform])
                   (get-in rt [:containers :platform]))]
    ["--platform" p]))

(defn- entrypoint [{ep :container/entrypoint cmd :container/cmd}]
  (cond
    ep
    ["--entrypoint" (str "'" (json/generate-string ep) "'")]
    (nil? cmd)
    ["--entrypoint" "/bin/sh"]))

(defn get-job-id [job build]
  "Creates a string representation of the job sid"
  (cs/join "-" (b/get-job-sid job build)))

(defn build-cmd-args
  "Builds command line args for the podman executable"
  [{:keys [job] :as rt}]
  (let [conf (mcc/job->container-config job)
        cn (get-job-id job (rt/build rt))
        wd (b/job-work-dir rt)
        cwd "/home/monkeyci"
        base-cmd (cond-> ["/usr/bin/podman" "run"
                          "-t"
                          "--name" cn
                          "-v" (str wd ":" cwd ":Z")
                          "-w" cwd]
                   ;; Do not delete container in dev mode
                   (not (rt/dev-mode? rt)) (conj "--rm"))]
    (concat
     ;; TODO Allow for more options to be passed in
     base-cmd
     (mounts job)
     (env-vars job)
     (platform rt)
     (entrypoint job)
     [(or (:image conf) (:image job))]
     (make-cmd job)
     ;; TODO Execute script job by job
     (make-script-cmd (:script job)))))

(defmethod mcc/run-container :podman [{:keys [job] :as rt}]
  (let [log-maker (rt/log-maker rt)
        build (rt/build rt)
        ;; Don't prefix the sid here, that's the responsability of the logger
        log-base (b/get-job-sid job build)
        [out-log err-log :as loggers] (->> ["out.txt" "err.txt"]
                                           (map (partial conj log-base))
                                           (map (partial log-maker build)))
        cmd (build-cmd-args rt)]
    (log/info "Running build job " log-base "as podman container")
    (log/debug "Log base is:" log-base)
    (log/debug "Podman command:" cmd)
    ((-> (fn [rt]
           (-> (bp/process {:dir (b/job-work-dir rt)
                            :out (l/log-output out-log)
                            :err (l/log-output err-log)
                            :cmd cmd})
               (l/handle-process-streams loggers)
               (deref)))
         (cache/wrap-caches)
         (art/wrap-artifacts))
     rt)))
