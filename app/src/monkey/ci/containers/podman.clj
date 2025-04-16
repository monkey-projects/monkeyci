(ns monkey.ci.containers.podman
  "Functions for running containers using Podman.  We don't use the api here, because
   it requires a socket, which is not always available.  Instead, we invoke the podman
   command as a child process and communicate with it using the standard i/o streams."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [artifacts :as art]
             [build :as b]
             [cache :as cache]
             [containers :as mcc]
             [jobs :as j]
             [protocols :as p]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.mailman :as em]
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
                           "-w" (if-let [jwd (j/work-dir job)]
                                  (str (fs/path cwd jwd))
                                  cwd)]
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
   ;; Deprecated
   (build-cmd-args job
                   (b/get-job-sid job build)
                   (b/checkout-dir build)
                   conf)))

;;; Mailman event handling

;;; Context management

(defn get-job
  ([ctx id]
   (some-> (emi/get-state ctx)
           (get-in [:jobs id])))
  ([ctx]
   (get-job ctx (get-in ctx [:event :job-id]))))

(defn set-job [ctx job]
  (emi/update-state ctx assoc-in [:jobs (:id job)] job))

(def get-work-dir
  "The directory where the container process is run"
  (comp ::work-dir emi/get-state))

(defn set-work-dir [ctx wd]
  (emi/update-state ctx assoc ::work-dir wd))

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

(defn restore-ws
  "Prepares the job working directory by restoring the files from the workspace."
  [workspace wd]
  {:name ::restore-ws
   :enter (fn [ctx]
            (let [job-dir (fs/path wd (get-in ctx [:event :job-id]))
                  dest (fs/create-dirs (fs/path job-dir "work"))
                  ws (ws/->BlobWorkspace workspace dest)]
              (log/debug "Restoring workspace to" dest)
              #_(fs/copy-tree src dest)
              (-> ctx
                  (assoc ::workspace @(p/restore-workspace ws (get-in ctx [:event :sid])))
                  (set-work-dir dest)
                  (set-log-dir (fs/create-dirs (fs/path job-dir "logs"))))))})

(def filter-container-job
  "Interceptor that terminates when the job in the event is not a container job"
  (emi/terminate-when ::filter-container-job
                      #(nil? (mcc/image (get-in % [:event :job])))))

(def save-job
  "Saves job to state for future reference"
  {:name ::save-job
   :enter (fn [ctx]
            (set-job ctx (get-in ctx [:event :job])))})

(def require-job
  "Terminates if no job is present in the state"
  (emi/terminate-when ::require-job #(nil? (get-job % (get-in % [:event :job-id])))))

(defn add-job-ctx
  "Adds the job context to the event context, and adds the job from state.  Also
   updates the build in the context so the checkout dir is the workspace dir."
  [initial-ctx]
  {:name ::add-job-ctx
   :enter (fn [ctx]
            (-> ctx
                (emi/set-job-ctx (-> initial-ctx
                                     (assoc :job (get-job ctx))
                                     (assoc-in [:build :checkout-dir] (get-work-dir ctx))))))})

;;; Event handlers

(def job-executed-evt
  "Creates an internal job-executed event, specifically for podman containers.  This is used
   as an intermediate step to save artifacts."
  (partial j/job-status-evt :podman/job-executed))

(defn prepare-child-cmd
  "Prepares podman command to execute as child process"
  [ctx]
  (let [job (get-in ctx [:event :job])
        log-file (comp fs/file (partial fs/path (get-log-dir ctx)))
        {:keys [job-id sid]} (:event ctx)]
    ;; TODO Prepare job script in separate dir and mount it in the container for execution
    {:cmd (build-cmd-args job
                          (conj sid job-id)
                          (get-work-dir ctx)
                          {})
     :dir (job-work-dir ctx job)
     :out (log-file "out.log")
     :err (log-file "err.log")
     :exit-fn (fn [{:keys [exit]}]
                (log/info "Container job exited with code" exit)
                (try
                  (em/post-events (emi/get-mailman ctx)
                                  [(job-executed-evt job-id sid (if (= 0 exit) bc/success bc/failure))])
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

(defn job-exec
  "Invoked after the podman container has exited.  Posts a job/executed event."
  [{{:keys [job-id sid status result]} :event}]
  [(j/job-executed-evt job-id sid (assoc result :status status))])

(defn- make-job-ctx [conf]
  (-> (select-keys conf [:artifacts :cache])
      (assoc :checkout-dir (b/checkout-dir (:build conf)))))

(defn make-routes [{:keys [workspace work-dir mailman] :as conf}]
  (let [state (emi/with-state (atom {}))
        job-ctx (make-job-ctx conf)
        wd (or work-dir (str (fs/create-temp-dir)))]
    (log/info "Creating podman container routes using work dir" wd)
    [[:container/job-queued
      [{:handler prepare-child-cmd
        :interceptors [emi/handle-job-error
                       state
                       save-job
                       (restore-ws workspace wd)
                       (emi/add-mailman mailman)
                       (add-job-ctx job-ctx)
                       (cache/restore-interceptor emi/get-job-ctx)
                       (art/restore-interceptor emi/get-job-ctx)
                       emi/start-process]}
       {:handler job-queued}]]

     [:job/initializing
      ;; TODO Start sidecar event polling
      [{:handler job-init
        :interceptors [emi/handle-job-error
                       state
                       require-job]}]]

     [:podman/job-executed
      [{:handler job-exec
        :interceptors [emi/handle-job-error
                       state
                       (add-job-ctx job-ctx)
                       (art/save-interceptor emi/get-job-ctx)
                       (cache/save-interceptor emi/get-job-ctx)]}]]]))
