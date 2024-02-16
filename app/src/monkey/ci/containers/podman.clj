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

(defn- platform [rt]
  (when-let [p (or (get-in rt [:step :container/platform])
                   (get-in rt [:containers :platform]))]
    ["--platform" p]))

(defn- entrypoint [{ep :container/entrypoint cmd :container/cmd}]
  (cond
    ep
    ["--entrypoint" (str "'" (json/generate-string ep) "'")]
    (nil? cmd)
    ["--entrypoint" "/bin/sh"]))

(defn build-cmd-args
  "Builds command line args for the podman executable"
  [{:keys [step] :as rt}]
  (let [conf (mcc/rt->container-config rt)
        cn (b/get-step-id rt)
        wd (b/step-work-dir rt)
        cwd "/home/monkeyci"
        base-cmd ["/usr/bin/podman" "run"
                  "-t" "--rm"
                  "--name" cn
                  ;; FIXME Some containers (notably kaniko) change ownership of the mount dir
                  "-v" (str wd ":" cwd ":Z")
                  "-w" cwd]]
    (concat
     ;; TODO Allow for more options to be passed in
     base-cmd
     (mounts step)
     (env-vars step)
     (platform rt)
     (entrypoint step)
     [(:image conf)]
     (make-cmd step)
     ;; TODO Execute script step by step
     (make-script-cmd (:script step)))))

(defmethod mcc/run-container :podman [{:keys [step pipeline] {:keys [build-id]} :build :as rt}]
  (log/info "Running build step " build-id "/" (:name step) "as podman container")
  (let [log-maker (rt/log-maker rt)
        ;; Don't prefix the sid here, that's the responsability of the logger
        log-base (b/get-step-sid rt)
        [out-log err-log :as loggers] (->> ["out.txt" "err.txt"]
                                           (map (partial conj log-base))
                                           (map (partial log-maker rt)))]
    (log/debug "Log base is:" log-base)
    ((-> (fn [_]
           (-> (bp/process {:dir (b/step-work-dir rt)
                            :out (l/log-output out-log)
                            :err (l/log-output err-log)
                            :cmd (build-cmd-args rt)})
               (l/handle-process-streams loggers)
               (deref)))
         (cache/wrap-caches)
         (art/wrap-artifacts))
     rt)))
