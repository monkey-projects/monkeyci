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
            [io.pedestal.interceptor.chain :as pi]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [build :as b]
             [cache :as cache]
             [containers :as mcc]
             [jobs :as j]
             [logging :as l]
             [protocols :as p]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.events.mailman.interceptors :as emi]))

;;; Process commandline configuration

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

(defn- get-job-id
  "Creates a string representation of the job sid"
  [job-sid]
  (cs/join "-" job-sid))

(defn build-cmd-args
  "Builds command line args for the podman executable"
  ([job job-sid wd opts]
   (let [cn (get-job-id job-sid)
         cwd "/home/monkeyci"
         base-cmd (cond-> ["/usr/bin/podman" "run"
                           "-t"
                           "--name" cn
                           "-v" (str wd ":" cwd ":Z")
                           "-w" cwd]
                    ;; Do not delete container in dev mode
                    (not (:dev-mode opts)) (conj "--rm"))]
     (concat
      ;; TODO Allow for more options to be passed in
      base-cmd
      (mounts job)
      (env-vars job)
      (platform job opts)
      (entrypoint job)
      [(mcc/image job)]
      (make-cmd job)
      ;; TODO Execute script command per command
      (make-script-cmd (:script job)))))
  ([job {:keys [build] :as conf}]
   (build-cmd-args job
                   (b/get-job-sid job build)
                   (b/job-work-dir job build)
                   conf)))

;;; Container runner implementation

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
                           (art/wrap-artifacts))
        handle-error (fn [ex]
                       (ec/post-events
                        events
                        (j/job-executed-evt (j/job-id job) (b/sid build) (ec/exception-result ex))))]
    (log/info "Running build job" log-base "as podman container")
    (log/debug "Podman command:" cmd)
    (ec/post-events events (j/job-start-evt (j/job-id job) (b/sid build)))
    ;; Job is required by the blob wrappers in the config
    (try
      (-> (wrapped-runner (assoc conf :job job))
          (md/chain
           (fn [{:keys [exit] :as res}]
             (ec/post-events events (j/job-executed-evt
                                     (j/job-id job)
                                     (b/sid build)
                                     (ec/make-result
                                      (b/exit-code->status exit)
                                      exit
                                      nil)))
             res))
          (md/catch handle-error))
      (catch Exception ex
        (handle-error ex)))))

(defrecord PodmanContainerRunner [config credit-consumer]
  p/ContainerRunner
  (run-container [this job]
    (run-container job config)))

(defn make-container-runner [conf]
  (->PodmanContainerRunner conf (constantly 0)))

;;; Mailman event handling

;;; Context management

(def get-build (comp :build emi/get-state))

(defn set-build [ctx b]
  (emi/update-state ctx assoc :build b))

(defn get-job [ctx id]
  (some-> (emi/get-state ctx)
          (get-in [:jobs id])))

(defn set-job [ctx job]
  (emi/update-state ctx assoc-in [:jobs (:id job)] job))

(def get-work-dir
  "The directory where the container process is run"
  ::work-dir)

(defn set-work-dir [ctx wd]
  (assoc ctx ::work-dir wd))

(def get-log-dir
  "The directory where container output is written to"
  ::log-dir)

(defn set-log-dir [ctx wd]
  (assoc ctx ::log-dir wd))

(defn job-work-dir [ctx job]
  (let [wd (j/work-dir job)]
    (cond-> (get-work-dir ctx)
      wd (u/abs-path wd))))

;;; Interceptors

(defn copy-ws
  "Prepares the job working directory by copying all files from `src`."
  [src wd]
  {:name ::copy-ws
   :enter (fn [ctx]
            (let [job-dir (fs/path wd (get-in ctx [:event :job-id]))
                  dest (fs/create-dirs (fs/path job-dir "work"))]
              (log/debug "Copying workspace from" src "to" dest)
              (fs/copy-tree src dest)
              (-> ctx
                  (set-work-dir dest)
                  (set-log-dir (fs/create-dirs (fs/path job-dir "logs"))))))})

(def handle-error
  {:name ::handle-error
   :error (fn [ctx ex]
            (let [{:keys [job-id sid] :as e} (:event ctx)]
              (log/error "Got error while handling event" e ex)
              (em/set-result ctx
                             [(j/job-end-evt job-id sid (-> bc/failure
                                                            (bc/with-message (ex-message ex))))])))})

(def filter-container-job
  "Interceptor that terminates when the job in the event is not a container job"
  {:name ::filter-container-job
   :enter (fn [ctx]
            (cond-> ctx
              (nil? (mcc/image (get-in ctx [:event :job]))) (pi/terminate)))})

(def save-job
  "Saves job to state for future reference"
  {:name ::save-job
   :enter (fn [ctx]
            (set-job ctx (get-in ctx [:event :job])))})

(def require-job
  "Terminates if no job is present in the state"
  {:name ::require-job
   :enter (fn [ctx]
            (cond-> ctx
              (nil? (get-job ctx (get-in ctx [:event :job-id]))) (pi/terminate)))})

;;; Event handlers

(defn prepare-child-cmd
  "Prepares podman command to execute as child process"
  [ctx]
  (let [build (get-build ctx)
        job (get-in ctx [:event :job])
        log-file (comp fs/file (partial fs/path (get-log-dir ctx)))
        {:keys [job-id sid]} (:event ctx)]
    ;; TODO Prepare job script in separate dir and mount it in the container for execution
    {:cmd (build-cmd-args job
                          (b/get-job-sid job build)
                          (get-work-dir ctx)
                          {})
     :dir (job-work-dir ctx job)
     :out (log-file "out.log")
     :err (log-file "err.log")
     :exit-fn (fn [{:keys [exit]}]
                (log/info "Container job exited with code" exit)
                (try
                  (em/post-events (emi/get-mailman ctx)
                                  [(j/job-executed-evt job-id sid {:status (if (= 0 exit) :success :failure)})])
                  (catch Exception ex
                    (log/error "Failed to post job/executed event" ex))))}))

(defn job-queued [ctx]
  (let [{:keys [job-id sid]} (:event ctx)]
    ;; Podman runs locally, so no credits consumed
    [(j/job-initializing-evt job-id sid 0)]))

(defn job-init [ctx]
  (let [{:keys [job-id sid]} (:event ctx)]
    ;; Ideally the container notifies us when it's running by means of a script,
    ;; similar to the oci sidecar.
    [(j/job-start-evt job-id sid)]))

(defn job-executed [ctx]
  (let [{:keys [job-id sid status result]} (:event ctx)]
    [(j/job-end-evt job-id sid (assoc result :status status))]))

(defn make-routes [{:keys [build workspace work-dir mailman] :as conf}]
  (let [state (emi/with-state (atom {:build build}))]
    [[:job/queued
      [{:handler prepare-child-cmd
        :interceptors [handle-error
                       filter-container-job
                       save-job
                       (copy-ws workspace work-dir)
                       (emi/add-mailman mailman)
                       ;; TODO Artifacts and caches
                       emi/start-process]}
       {:handler job-queued}]]

     [:job/initializing
      ;; TODO Start sidecar event polling
      ;; TODO Execute "before" extensions
      [{:handler job-init
        :interceptors [handle-error
                       require-job]}]]

     [:job/executed
      ;; TODO Execute "after" extensions
      [{:handler job-executed
        :interceptors [handle-error
                       require-job]}]]

     [:job/end
      ;; TODO Clean up?
      [{:handler (constantly nil)}]]]))
