(ns monkey.ci.containers.podman
  "Functions for running containers using Podman.  We don't use the api here, because
   it requires a socket, which is not always available.  Instead, we invoke the podman
   command as a child process and communicate with it using the standard i/o streams."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [cheshire.core :as json]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [artifacts :as art]
             [build :as b]
             [cache :as cache]
             [containers :as mcc]
             [jobs :as j]
             [logging :as l]
             [protocols :as p]
             [runtime :as rt]]
            [monkey.ci.events.core :as ec]))

(defn- make-script-cmd [script]
  [(cs/join " && " script)])

(defn- make-cmd [job]
  (if-let [cmd (mcc/cmd job)]
    cmd
    ;; When no command is given, use /bin/sh as entrypoint and fail on errors
    ["-ec"]))

(defn- mounts [job]
  (mapcat (fn [[h c]]
            ;; TODO Mount options
            ["-v" (str h ":" c)])
          (mcc/mounts job)))

(defn- env-vars [job]
  (mapcat (fn [[k v]]
            ["-e" (str k "=" v)])
          (mcc/env job)))

(defn- platform [job conf]
  (when-let [p (or (mcc/platform job)
                   (:platform conf))]
    ["--platform" p]))

(defn- entrypoint [job]
  (let [ep (mcc/entrypoint job)]
    (cond
      ep
      ["--entrypoint" (str "'" (json/generate-string ep) "'")]
      (nil? (mcc/cmd job))
      ["--entrypoint" "/bin/sh"])))

(defn get-job-id [job build]
  "Creates a string representation of the job sid"
  (cs/join "-" (b/get-job-sid job build)))

(defn build-cmd-args
  "Builds command line args for the podman executable"
  [job {:keys [build] :as conf}]
  (let [cn (get-job-id job build)
        wd (b/job-work-dir job build)
        cwd "/home/monkeyci"
        base-cmd (cond-> ["/usr/bin/podman" "run"
                          "-t"
                          "--name" cn
                          "-v" (str wd ":" cwd ":Z")
                          "-w" cwd]
                   ;; Do not delete container in dev mode
                   (not (:dev-mode? conf)) (conj "--rm"))]
    (concat
     ;; TODO Allow for more options to be passed in
     base-cmd
     (mounts job)
     (env-vars job)
     (platform job conf)
     (entrypoint job)
     [(mcc/image job)]
     (make-cmd job)
     ;; TODO Execute script job by job
     (make-script-cmd (:script job)))))

(defn- run-container [job {:keys [build events] :as conf}]
  (let [log-maker (rt/log-maker conf)
        ;; Don't prefix the sid here, that's the responsability of the logger
        log-base (b/get-job-sid job build)
        [out-log err-log :as loggers] (->> ["out.txt" "err.txt"]
                                           (map (partial conj log-base))
                                           (map (partial log-maker build)))
        cmd (build-cmd-args job conf)
        wrapped-runner (-> (fn [conf]
                             (-> (bp/process {:dir (b/job-work-dir job (:build conf))
                                              :out (l/log-output out-log)
                                              :err (l/log-output err-log)
                                              :cmd cmd})
                                 (l/handle-process-streams loggers)
                                 (deref)))
                           (cache/wrap-caches)
                           (art/wrap-artifacts))]
    (log/info "Running build job " log-base "as podman container")
    (log/debug "Log base is:" log-base)
    (log/debug "Podman command:" cmd)
    (ec/post-events events (j/job-start-evt (j/job-id job) (b/sid build)))
    ;; Job is required by the blob wrappers in the config
    (try 
      (let [{:keys [exit] :as res} (wrapped-runner (assoc conf :job job))]
        (ec/post-events events (j/job-executed-evt
                                (j/job-id job)
                                (b/sid build)
                                (ec/make-result
                                 (b/exit-code->status exit)
                                 exit
                                 nil)))
        res)
      (catch Exception ex
        (ec/post-events events (j/job-executed-evt (j/job-id job) (b/sid build) (ec/exception-result ex)))))))

(defrecord PodmanContainerRunner [config credit-consumer]
  p/ContainerRunner
  (run-container [this job]
    (run-container job config)))

(defn make-container-runner [conf]
  (->PodmanContainerRunner conf (constantly 0)))
