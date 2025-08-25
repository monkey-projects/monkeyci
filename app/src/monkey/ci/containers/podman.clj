(ns monkey.ci.containers.podman
  "Functions for running containers using Podman.  We don't use the api here, because
   it requires a socket, which is not always available.  Instead, we invoke the podman
   command as a child process and communicate with it using the standard i/o streams."
  (:require [babashka.fs :as fs]
            [buddy.core.codecs :as bcc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [artifacts :as art]
             [build :as b]
             [cache :as cache]
             [containers :as mcc]
             [jobs :as j]
             [process :as proc]
             [protocols :as p]
             [utils :as u]
             [vault :as v]
             [workspace :as ws]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.containers.common :as cc]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.vault.common :as vc]))

;;; Process commandline configuration

(def reserved-vars
  "List of reserved env vars, used by podman that can override its behaviour.  When a
   job specifies one of these vars, they should be explicitly passed on the commandline
   and not as process env vars (which would be a potential security breach)."
  #{"CONTAINERS_CONF"
    "CONTAINERS_REGISTRIES_CONF"
    "CONTAINERS_REGISTRIES_CONF_DIR"
    "CONTAINERS_STORAGE_CONF"
    "CONTAINER_CONNECTION"
    "CONTAINER_HOST"
    "CONTAINER_SSHKEY"
    "DBUS_SESSION_BUS_ADDRESS"
    "DOCKER_CONFIG"
    "STORAGE_DRIVER"
    "STORAGE_OPTS"
    "TMPDIR"
    "XDG_CONFIG_HOME"
    "XDG_DATA_HOME"
    "XDG_RUNTIME_DIR"})

(defn- reserved? [var]
  (or (reserved-vars var)
      ;; Also exclude all env vars that start with this
      (cs/starts-with? var "PODMAN_")))

(defn- make-script-cmd [script sd]
  (->> (range (count script))
       (map str)
       (into [(str sd "/" cc/job-script)])))

(defn- make-cmd [job sd]
  (if-let [cmd (mcc/cmd job)]
    cmd
    ;; When no command is given, run the script
    (make-script-cmd (:script job) sd)))

(defn- mounts [job]
  (mapcat (fn [[h c]]
            ;; TODO Mount options
            ["-v" (str h ":" c)])
          (mcc/mounts job)))

(defn- env-vars [env]
  (mapcat (fn [[k v]]
            ["-e" (cond-> k
                    v (str "=" v))])
          env))

(defn- strip-reserved-env [env]
  (->> env
       (mc/map-kv-vals (fn [k v]
                         (when (reserved? k) v)))))

(defn arch-arg [arch]
  (str (name arch) "64"))

(defn- arch [job conf]
  (when-let [a (or (mcc/arch job)
                   (mcc/arch conf))]
    ["--arch" (arch-arg a)]))

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

(defn- vol-mnt [from to]
  (str from ":" to ":Z"))

(defn- script->files [script dest]
  (fs/create-dirs dest)
  (->> script
       (map-indexed (fn [idx l]
                      (spit (fs/file (fs/path dest (str idx))) l)))
       (doall)))

(defn podman-cmd [opts]
  (get opts :podman-cmd "/usr/bin/podman"))

(defn build-cmd-args
  "Builds command line args for the podman executable"
  [{:keys [job sid] base :work-dir sd :script-dir :as opts}]
  (let [cn (get-job-id sid)
        cwd "/home/monkeyci"
        ext-dir "/opt/monkeyci"
        csd (str ext-dir "/script")
        cld (str ext-dir "/logs")
        wd (if-let [jwd (j/work-dir job)]
             (str (fs/path cwd jwd))
             cwd)
        start "start"
        base-cmd (cond-> [(podman-cmd opts) "run"
                          "-t"
                          "--name" cn
                          ;; TODO This is not always available, add an auto-check
                          ;; See https://github.com/containers/podman/blob/main/troubleshooting.md#26-running-containers-with-cpu-limits-fails-with-a-permissions-error
                          "--cpus" (str (j/size->cpus job))
                          "--memory" (str (j/size->mem job) "g")
                          ;; TODO Use volumes with limited size, to prevent jobs
                          ;; from filling up the entire agent disk.
                          "-v" (vol-mnt base cwd)
                          "-v" (vol-mnt sd csd)
                          "-v" (vol-mnt (:log-dir opts) cld)
                          "-w" wd
                          ;; TODO Allow arbitrary additional command line opts
                          ]
                   ;; Do not delete container in dev mode
                   (not (:dev-mode opts)) (conj "--rm"))
        env {"MONKEYCI_WORK_DIR" wd
             "MONKEYCI_SCRIPT_DIR" csd
             "MONKEYCI_LOG_DIR" cld
             "MONKEYCI_START_FILE" (str csd "/" start)
             "MONKEYCI_ABORT_FILE" (str csd "/abort")
             "MONKEYCI_EVENT_FILE" (str csd "/events.edn")}]
    (when-let [s (:script job)]
      (script->files s sd)
      (io/copy (slurp (io/resource cc/job-script)) (fs/file (fs/path sd cc/job-script)))
      ;; Auto start, so touch the start file immediately
      (let [sf (fs/path sd start)]
        (when-not (fs/exists? sf)
          (fs/create-file sf))))
    (concat
     base-cmd
     (mounts job)
     ;; For security purposes, we do not specify the env values on the command
     ;; line.  Instead, we pass only the names without values, which makes podman
     ;; take the values from the process env instead.
     ;; See https://docs.podman.io/en/latest/markdown/podman-run.1.html#env-e-env
     ;; Only reserved env vars are passed entirely, otherwise execution may break.
     (env-vars (merge (strip-reserved-env (mcc/env job))
                      env))
     (arch job opts)
     (entrypoint job)
     [(mcc/image job)]
     (make-cmd job csd))))

;;;; Mailman event handling

;;; Context management

(def build-sid (comp :sid :event))
(def ctx->job-id (comp :job-id :event))

(defn get-job
  ([ctx id]
   (some-> (emi/get-state ctx)
           (get-in [:jobs (build-sid ctx) id])))
  ([ctx]
   (get-job ctx (ctx->job-id ctx))))

(defn count-jobs [state]
  (get state :job-count 0))

(defn set-job [ctx job]
  (emi/update-state ctx assoc-in [:jobs (build-sid ctx) (:id job)] job))

(def get-job-dir
  "The directory where files for this job are put"
  (comp ::job-dir emi/get-state))

(defn set-job-dir [ctx wd]
  (emi/update-state ctx assoc ::job-dir wd))

(def get-work-dir
  "The directory where the container process is run"
  (comp #(fs/path % "work") get-job-dir))

(def get-log-dir
  "The directory where container output is written to"
  (comp #(fs/path % "logs") get-job-dir))

(def get-script-dir
  "The directory where script files are stored"
  (comp #(fs/path % "script") get-job-dir))

(defn job-work-dir [ctx job]
  (let [wd (j/work-dir job)]
    (cond-> (str (get-work-dir ctx))
      wd (u/abs-path wd))))

(defn set-key-decrypter [ctx kd]
  (assoc ctx ::key-decrypter kd))

(def get-key-decrypter ::key-decrypter)

(def podman-opts
  "Additional podman options"
  ::podman-opts)

(defn set-podman-opts [ctx opts]
  (assoc ctx podman-opts opts))

;;; Interceptors

(defn add-job-dir
  "Adds the directory for the job files in the event to the context"
  [wd]
  {:name ::add-job-dir
   :enter (fn [ctx]
            (->> (conj (build-sid ctx) (ctx->job-id ctx))
                 (apply fs/path wd)
                 (str)
                 (set-job-dir ctx)))})

(defn add-key-decrypter [kd]
  {:name ::add-key-decrypter
   :enter (fn [ctx]
            (set-key-decrypter ctx kd))})

(defn restore-ws
  "Prepares the job working directory by restoring the files from the workspace."
  [workspace]
  {:name ::restore-ws
   :enter (fn [ctx]
            (let [dest (fs/create-dirs (get-work-dir ctx))
                  ws (ws/->BlobWorkspace workspace (str dest))]
              (log/debug "Restoring workspace to" dest)
              (assoc ctx ::workspace (-> (p/restore-workspace ws (build-sid ctx))
                                         (u/maybe-deref)))))})

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
  "Terminates if the event job is not present in the state"
  (emi/terminate-when ::require-job #(nil? (get-job % (ctx->job-id %)))))

(defn add-job-ctx
  "Adds the job context to the event context, and adds the job from state.  Also
   updates the build in the context so the checkout dir is the workspace dir."
  [initial-ctx]
  {:name ::add-job-ctx
   :enter (fn [ctx]
            (-> ctx
                (emi/set-job-ctx (-> initial-ctx
                                     (assoc :job (get-job ctx)
                                            :sid (build-sid ctx)
                                            :checkout-dir (str (get-work-dir ctx)))))))})

(defn cleanup 
  "Deletes files after container job has finished"
  [{:keys [cleanup?]}]
  {:name ::cleanup
   :leave (fn [ctx]
            (when cleanup?
              (let [jd (get-job-dir ctx)]
                (log/debug "Deleting job dir" jd)
                (fs/delete-tree jd)))
            ctx)})

(def remove-job
  "Interceptor that removes the job from the state, for cleanup"
  (letfn [(clean-job [ctx jobs]
            (let [upd (-> jobs
                          (get (build-sid ctx))
                          (dissoc (ctx->job-id ctx)))]
              ;; When no more jobs remain, remove the entire sid from state
              (let [j (if (empty? upd)
                        (dissoc jobs (build-sid ctx))
                        (assoc jobs (build-sid ctx) upd))]
                ;; Since state is deep merged with old state, we need to replace the
                ;; empty jobs with `nil` otherwise they won't be cleared.
                (if (empty? j) nil j))))]
    {:name ::remove-job
     :leave (fn [ctx]
              (emi/update-state
               ctx
               (fn [state]
                 (update state :jobs (partial clean-job ctx)))))}))

(def inc-job-count
  {:name ::inc-job-count
   :leave (fn [ctx]
            (emi/update-state ctx update :job-count (fnil inc 0)))})

(def dec-job-count
  {:name ::dec-job-count
   :leave (fn [ctx]
            (emi/update-state ctx update :job-count (comp (partial max 0)
                                                          (fnil dec 0))))})

(def decrypt-env
  "Interceptor that decrypts the env vars for an incoming job.  The job event
   should contain a data encryption key that is used in conjunction with the
   org id for decryption."
  (letfn [(decrypt [env decrypter]
            (mc/map-vals @decrypter env))]
    {:name ::decrypt-env
     :enter (fn [ctx]
              (let [sid (build-sid ctx)
                    org-id (b/sid->org-id sid)
                    decrypter (delay
                                ;; Get and decrypt key
                                (let [k @((get-key-decrypter ctx)
                                          (get-in ctx [:event :dek])
                                          sid)
                                      dek (bcc/b64->bytes k)
                                      iv (v/cuid->iv org-id)]
                                  (log/debug "Decrypted dek:" k)
                                  (fn [x]
                                    (vc/decrypt dek iv x))))]
                (log/debug "Decrypting env vars for job" (ctx->job-id ctx))
                (update-in ctx [:event :job]
                           mc/update-existing :container/env decrypt decrypter)))}))

(defn add-podman-opts
  "Adds podman options to the mailman context"
  [opts]
  {:name ::set-podman-opts
   :enter (fn [ctx]
            (set-podman-opts ctx opts))})

;;; Event handlers

(def container-end-evt
  "Creates a `container/end` event, specifically for podman containers.  This is used
   as an intermediate step to save artifacts."
  (partial j/job-status-evt :container/end))

(defn prepare-child-cmd
  "Prepares podman command to execute as child process"
  [ctx]
  (let [job (get-in ctx [:event :job])
        log-file (comp fs/file (partial fs/path (fs/create-dirs (get-log-dir ctx))))
        {:keys [job-id sid]} (:event ctx)]
    {:cmd (->> {:job job
                :sid (conj sid job-id)
                :work-dir (get-work-dir ctx)
                :log-dir (get-log-dir ctx)
                :script-dir (get-script-dir ctx)}
               (merge (podman-opts ctx))
               (build-cmd-args))
     :dir (job-work-dir ctx job)
     :out (log-file "out.log")
     :err (log-file "err.log")
     ;; Pass the job env to the process.  These are then passed on to the container.
     :extra-env (->> (mcc/env job)
                     (mc/filter-keys (complement reserved?)))
     :exit-fn (proc/exit-fn
               (fn [{:keys [exit]}]
                 (log/info "Container job exited with code" exit)
                 (try
                   (em/post-events (emi/get-mailman ctx)
                                   [(container-end-evt job-id sid (if (= 0 exit) bc/success bc/failure))])
                   (catch Exception ex
                     (log/error "Failed to post job/executed event" ex)))))}))

(defn job-queued [conf ctx]
  (let [{:keys [job-id sid]} (:event ctx)]
    [(-> (j/job-initializing-evt job-id sid (:credit-multiplier conf))
         (assoc :local-dir (get-job-dir ctx)))]))

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

(defn make-routes [{:keys [workspace work-dir mailman state] :as conf}]
  (let [state (emi/with-state (or state (atom {})))
        job-ctx (make-job-ctx conf)
        wd (or work-dir (str (fs/create-temp-dir)))]
    (log/info "Creating podman container routes using work dir" wd)
    (log/debug "Additional podman options:" (:podman conf))
    [[:container/job-queued
      [{:handler prepare-child-cmd
        :interceptors [emi/handle-job-error
                       emi/no-result
                       state
                       save-job
                       inc-job-count
                       (add-job-dir wd)
                       (add-key-decrypter (:key-decrypter conf))
                       (restore-ws workspace)
                       (emi/add-mailman mailman)
                       (add-job-ctx job-ctx)
                       (cache/restore-interceptor emi/get-job-ctx)
                       (art/restore-interceptor emi/get-job-ctx)
                       decrypt-env
                       emi/start-process
                       (add-podman-opts (:podman conf))]}
       {:handler (partial job-queued conf)
        :interceptors [(add-job-dir wd)]}]]

     [:job/initializing
      ;; TODO Start polling for events from events.edn?
      [{:handler job-init
        :interceptors [emi/handle-job-error
                       state
                       require-job]}]]

     [:container/end
      [{:handler job-exec
        :interceptors [emi/handle-job-error
                       state
                       require-job
                       remove-job
                       dec-job-count
                       (cleanup conf)
                       (add-job-dir wd)
                       (add-job-ctx job-ctx)
                       (art/save-interceptor emi/get-job-ctx)
                       (cache/save-interceptor emi/get-job-ctx)]}]]]))
