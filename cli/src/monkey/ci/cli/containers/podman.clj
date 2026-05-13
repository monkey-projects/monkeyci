(ns monkey.ci.cli.containers.podman
  "Runs container jobs using Podman by invoking the `podman` CLI as a child
   process.  GraalVM-native-compatible port of monkey.ci.containers.podman
   from app/.

   Key differences from the app/ version:
     - manifold streams replaced by core.async channels
     - events/edn uses the core/ version (blocking thread, not manifold future)
     - utils/wait-for-file uses the core/ version (Thread/sleep, not manifold)
     - decrypt-env interceptor omitted (DEK decryption out of scope for local builds)
     - inetaddr->str uses plain java.net.InetAddress"
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [cheshire.core :as json]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [containers :as mcc]
             [jobs :as j]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.cli
             [events :as ce]
             [process :as p]]
            [monkey.ci.events
             [builders :as eb]
             [edn :as ee]]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.utils
             [io :as uio]
             [map :as um]]
            [monkey.mailman.core :as mmc]))

;;;; ─── Reserved env-var handling ────────────────────────────────────────────

(def reserved-vars
  "Podman environment variables that must be passed on the command line (not as
   process env vars) to prevent a security breach."
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

(def events-file "events.edn")

(defn reserved?
  "True if `var` is a Podman-reserved environment variable name."
  [var]
  (or (reserved-vars var)
      (cs/starts-with? var "PODMAN_")))

;;;; ─── Command-line building ─────────────────────────────────────────────────

(defn- make-script-cmd [script sd]
  (->> (range (count script))
       (map str)
       (into [(str sd "/job.sh")])))

(defn- make-cmd [job sd]
  (if-let [cmd (mcc/cmd job)]
    cmd
    (make-script-cmd (:script job) sd)))

(defn- mounts [job]
  (mapcat (fn [[h c]] ["-v" (str h ":" c)])
          (mcc/mounts job)))

(defn- env-vars [env]
  (mapcat (fn [[k v]]
            ["-e" (cond-> k v (str "=" v))])
          env))

(defn- strip-reserved-env [env]
  (mc/map-kv-vals (fn [k v]
                    (when (reserved? k) v))
                  env))

(defn arch-arg [arch]
  (str (name arch) "64"))

(defn- arch [job opts]
  (when-let [a (or (mcc/arch job) (mcc/arch opts))]
    ["--arch" (arch-arg a)]))

(defn- entrypoint [job]
  (let [ep (mcc/entrypoint job)]
    (cond
      ep
      ["--entrypoint" (str "'" (json/generate-string ep) "'")]
      (nil? (mcc/cmd job))
      ["--entrypoint" "/bin/sh"])))

(defn- get-job-id [job-sid]
  (cs/join "-" job-sid))

(defn- vol-mnt [from to]
  (str from ":" to ":Z"))

(defn- script->files [script dest]
  (fs/create-dirs dest)
  (doall
   (map-indexed (fn [idx l]
                  (spit (fs/file (fs/path dest (str idx))) l))
                script)))

(defn podman-cmd [opts]
  (get opts :podman-cmd "podman"))

(defn find-avail-ports
  "Returns the first `n` available ports from `port-range` that are not in `used`."
  [n port-range used]
  (take n (->> (apply range port-range)
               (remove used))))

(defn- expose-ports [cmd ports]
  (->> ports
       (mapcat (fn [[cp hp]] ["-p" (str hp ":" cp)]))
       (concat cmd)
       vec))

(defn- find-res [f]
  (some-> f
          (io/resource)
          (slurp)))

(defn load-job-script []
  (find-res "job.sh"))

(defn build-cmd-args
  "Builds the podman run command-line arguments vector."
  [{:keys [job sid mapped-ports] base :work-dir sd :script-dir :as opts}]
  (let [cn      (get-job-id sid)
        cwd     "/home/monkeyci"
        ext-dir "/opt/monkeyci"
        csd     (str ext-dir "/script")
        cld     (str ext-dir "/logs")
        wd      (if-let [jwd (j/work-dir job)]
                  (str (fs/path cwd jwd))
                  cwd)
        start   "start"
        base-cmd (cond-> [(podman-cmd opts) "run"
                          "-t"
                          "--name" cn
                          "--cpus" (str (j/size->cpus job))
                          "--memory" (str (j/size->mem job) "g")
                          "-v" (vol-mnt base cwd)
                          "-v" (vol-mnt sd csd)
                          "-v" (vol-mnt (:log-dir opts) cld)
                          "-w" wd]
                   (not-empty mapped-ports) (expose-ports mapped-ports)
                   (not (:dev-mode opts)) (conj "--rm"))
        env {"MONKEYCI_WORK_DIR"   wd
             "MONKEYCI_SCRIPT_DIR" csd
             "MONKEYCI_LOG_DIR"    cld
             "MONKEYCI_START_FILE" (str csd "/" start)
             "MONKEYCI_ABORT_FILE" (str csd "/abort")
             "MONKEYCI_EVENT_FILE" (str csd "/" events-file)}]
    (when-let [s (:script job)]
      (script->files s sd)
      (if-let [r (find-res "job.sh")]
        (io/copy r (fs/file (fs/path sd "job.sh")))
        (log/warn "Unable to find job script in any of the configured paths"))
      (let [sf (fs/path sd start)]
        (when-not (fs/exists? sf)
          (fs/create-file sf))))
    (concat
     base-cmd
     (mounts job)
     (env-vars (merge (strip-reserved-env (mcc/env job)) env))
     (arch job opts)
     (entrypoint job)
     [(mcc/image job)]
     (make-cmd job csd))))

;;;; ─── Context management ────────────────────────────────────────────────────

(def build-sid (comp :sid :event))
(def ctx->job-id (comp :job-id :event))

(defn get-job
  ([ctx id]
   (some-> (emi/get-state ctx)
           (get-in [:jobs (build-sid ctx) id])))
  ([ctx]
   (get-job ctx (ctx->job-id ctx))))

(defn set-job [ctx job]
  (emi/update-state ctx assoc-in [:jobs (build-sid ctx) (:id job)] job))

(def get-job-dir (comp ::job-dir emi/get-state))

(defn set-job-dir [ctx wd]
  (emi/update-state ctx assoc ::job-dir wd))

(defn get-job-timeout [ctx]
  (min (or (some-> (get-job ctx) :timeout)
           j/default-job-timeout)
       j/max-job-timeout))

(def get-work-dir (comp #(fs/path % "work") get-job-dir))

(defn calc-log-dir [jd] (fs/path jd "logs"))

(def get-log-dir (comp calc-log-dir get-job-dir))

(def get-script-dir (comp #(fs/path % "script") get-job-dir))

(defn get-events-ch [ctx]
  (some-> (emi/get-state ctx)
          (get-in [:events-chs (build-sid ctx) (ctx->job-id ctx)])))

(defn set-events-ch [ctx ch]
  (emi/update-state ctx assoc-in [:events-chs (build-sid ctx) (ctx->job-id ctx)] ch))

(defn remove-events-ch [ctx]
  (emi/update-state ctx (fn [s]
                          (-> s
                              (update-in [:events-chs (build-sid ctx)] dissoc (ctx->job-id ctx))
                              (update :events-chs um/prune-tree)))))

(defn set-mapped-ports [ctx p]
  (emi/update-state ctx assoc ::mapped-ports p))

(def get-mapped-ports (comp ::mapped-ports emi/get-state))

(defn update-mapped-ports [ctx f & args]
  (set-mapped-ports ctx (apply f (get-mapped-ports ctx) args)))

(defn get-job-mapped-ports [ctx]
  (-> (get-mapped-ports ctx)
      (get-in [(build-sid ctx) (ctx->job-id ctx)])))

(def podman-opts ::podman-opts)

(defn set-podman-opts [ctx opts]
  (assoc ctx podman-opts opts))

(defn add-key-decrypter [kd]
  {:name ::add-key-decrypter
   :enter (fn [ctx] (assoc ctx ::key-decrypter kd))})

(defn podman-src [evt]
  (assoc evt :src :podman))

;;;; ─── Interceptors ──────────────────────────────────────────────────────────

(defn add-job-dir
  "Interceptor that sets the per-job working directory in context state."
  [wd]
  {:name ::add-job-dir
   :enter (fn [ctx]
            (->> (conj (build-sid ctx) (ctx->job-id ctx))
                 (apply fs/path wd)
                 str
                 (set-job-dir ctx)))})

(defn restore-ws
  "Copies workspace files into the container's work directory.  Uses the
   workspace path stored in context state by the `save-workspace` interceptor."
  []
  {:name ::restore-ws
   :enter (fn [ctx]
            (let [ws (ce/get-workspace ctx)
                  dest (fs/create-dirs (get-work-dir ctx))]
              (if ws
                (do
                  (log/debug "Restoring workspace for job"
                             (ctx->job-id ctx) "from" ws "to" dest)
                  (fs/copy-tree (fs/path ws) dest {:replace-existing true})
                  ctx)
                (do
                  (log/debug "No workspace in state, skipping restore for job"
                             (ctx->job-id ctx))
                  ctx))))})

(def filter-container-job
  "Terminates the interceptor chain when the event's job has no container image."
  (emi/terminate-when ::filter-container-job
                      #(nil? (mcc/image (get-in % [:event :job])))))

(def save-job
  "Saves the job from the event into context state for later reference."
  {:name ::save-job
   :enter (fn [ctx]
            (set-job ctx (get-in ctx [:event :job])))})

(def require-job
  "Terminates if the event's job is not found in state."
  (emi/terminate-when ::require-job #(nil? (get-job % (ctx->job-id %)))))

(defn add-job-ctx
  "Enriches the context with a job context map containing the job from state."
  [initial-ctx]
  {:name ::add-job-ctx
   :enter (fn [ctx]
            (emi/set-job-ctx ctx (-> initial-ctx
                                     (assoc :job (get-job ctx)
                                            :sid (build-sid ctx)
                                            :checkout-dir (str (get-work-dir ctx))))))})

(defn cleanup
  "Deletes the job's working directory on `:leave` when `cleanup?` is true."
  [{:keys [cleanup?]}]
  {:name ::cleanup
   :leave (fn [ctx]
            (when cleanup?
              (let [jd (get-job-dir ctx)]
                (log/debug "Deleting job dir" jd)
                (fs/delete-tree jd)))
            ctx)})

(def remove-job
  "Removes the current job from state, cleaning up after completion."
  (letfn [(clean-job [ctx jobs]
            (let [upd (-> jobs (get (build-sid ctx)) (dissoc (ctx->job-id ctx)))]
              (let [j (if (empty? upd)
                        (dissoc jobs (build-sid ctx))
                        (assoc jobs (build-sid ctx) upd))]
                (when-not (empty? j) j))))]
    {:name ::remove-job
     :leave (fn [ctx]
              (emi/update-state ctx #(update % :jobs (partial clean-job ctx))))}))

(def inc-job-count
  {:name ::inc-job-count
   :leave (fn [ctx]
            (emi/update-state ctx update :job-count (fnil inc 0)))})

(def dec-job-count
  {:name ::dec-job-count
   :leave (fn [ctx]
            (emi/update-state ctx update :job-count (comp (partial max 0) (fnil dec 0))))})

(defn add-podman-opts
  "Adds podman CLI options to the context."
  [opts]
  {:name ::add-podman-opts
   :enter (fn [ctx] (set-podman-opts ctx opts))})

(defn assign-ports
  "Assigns available host ports for job-exposed container ports."
  [port-range]
  (letfn [(calc-used [m]
            (->> m vals (mapcat vals) (mapcat vals) set))]
    {:name ::assign-ports
     :enter (fn [ctx]
              (let [ports (:expose (get-job ctx))]
                (update-mapped-ports
                 ctx
                 (fn [mapped]
                   (->> (find-avail-ports (count ports) port-range (calc-used mapped))
                        (zipmap ports)
                        (assoc-in mapped [(build-sid ctx) (ctx->job-id ctx)]))))))}))

(def release-ports
  "Releases host ports assigned to the current job."
  (letfn [(maybe-remove-build [m sid]
            (cond-> m (empty? (get m sid)) (dissoc sid)))]
    {:name ::release-ports
     :enter (fn [ctx]
              (update-mapped-ports
               ctx
               (fn [mapped]
                 (let [sid (build-sid ctx)]
                   (-> mapped
                       (update sid dissoc (ctx->job-id ctx))
                       (maybe-remove-build sid))))))}))

(defn job-queued-result
  "Sets the `:job/initializing` event as the result, including the job directory."
  [conf]
  {:name ::job-queued
   :leave (fn [ctx]
            (let [{:keys [job-id sid]} (:event ctx)
                  m (get-job-mapped-ports ctx)]
              (emi/set-result
               ctx
               [(-> (j/job-initializing-evt job-id sid (:credit-multiplier conf))
                    (assoc :local-dir (get-job-dir ctx)))])))})

(def watch-events
  "Starts watching the events file written by the container's job.sh script.
   Events are read on a background thread and posted to mailman.
   Replaces the manifold-stream-based watcher from app/."
  {:name ::watch-events
   :leave (fn [ctx]
            (let [e      (fs/path (get-script-dir ctx) events-file)
                  ch     (ca/chan 10)
                  sid    (build-sid ctx)
                  job-id (ctx->job-id ctx)
                  augment (fn [evt]
                            (-> evt
                                podman-src
                                (assoc :sid sid :job-id job-id)))]
              ;; Consumer: drain channel → post to mailman
              (ca/go-loop []
                (when-let [evt (ca/<! ch)]
                  (mmc/post-events (emi/get-mailman ctx) [evt])
                  (recur)))
              ;; Producer: tail events file → put on channel
              (ee/read-edn
               (io/reader (fs/file (uio/wait-for-file e)))
               (-> (fn [evt]
                     (ca/put! ch (augment evt))
                     true)
                   (ee/sleep-on-eof 100)
                   (ee/stop-on-file-delete e)))
              (-> ctx
                  (set-events-ch ch))))})

(def stop-watch-events
  "Closes the events channel for the current job, stopping the watcher."
  {:name ::stop-watch-events
   :enter (fn [ctx]
            (let [ch (get-events-ch ctx)]
              (when ch
                (ca/close! ch))
              (cond-> ctx ch (remove-events-ch))))})

;;;; ─── Event handlers ────────────────────────────────────────────────────────

(def container-end-evt
  "Creates a `:container/end` event tagged with podman source."
  (comp podman-src (partial j/job-status-evt :container/end)))

(defn prepare-child-cmd
  "Builds the podman process command map for `start-process` to execute."
  [ctx]
  (let [job                      (get-in ctx [:event :job])
        log-file                 (comp fs/file (partial fs/path (fs/create-dirs (get-log-dir ctx))))
        {:keys [job-id sid]}     (:event ctx)]
    {:cmd (->> {:job          job
                :sid          (conj sid job-id)
                :work-dir     (get-work-dir ctx)
                :log-dir      (get-log-dir ctx)
                :script-dir   (get-script-dir ctx)
                :mapped-ports (get-job-mapped-ports ctx)}
               (merge (podman-opts ctx))
               build-cmd-args)
     :dir (if-let [jwd (j/work-dir job)]
            (str (fs/path (get-work-dir ctx) jwd))
            (str (get-work-dir ctx)))
     :out (log-file "out.log")
     :err (log-file "err.log")
     :extra-env (->> (mcc/env job) (mc/filter-keys (complement reserved?)))
     :exit-fn (p/exit-fn
               (fn [{:keys [exit]}]
                 (log/info "Container job" job-id "exited with code" exit)
                 (try
                   (mmc/post-events (emi/get-mailman ctx)
                                    [(container-end-evt job-id sid
                                                        (if (zero? exit) bc/success bc/failure))])
                   (log/info "Event posted")
                   (catch Exception ex
                     (log/error "Failed to post container/end event" ex)))))}))

(defn job-init [ctx]
  (let [job                  (get-job ctx)
        {:keys [job-id sid]} (:event ctx)]
    (when (empty? (:script job))
      [(podman-src (j/job-start-evt job-id sid))])))

(defn job-pending [ctx]
  (let [{:keys [job-id sid]} (:event ctx)]
    [(podman-src (j/job-start-evt job-id sid))]))

(defn job-exec [{{:keys [job-id sid status result]} :event}]
  [(podman-src (j/job-executed-evt job-id sid (assoc result :status status)))])

;;;; ─── Route factory ─────────────────────────────────────────────────────────

(def default-expose-range [20000 21000])

(defn make-routes
  "Creates mailman route entries for Podman container jobs.

   `conf` map keys:
     :work-dir      — base directory for per-job subdirectories (required)
     :mailman       — mailman broker (required)
     :state         — optional shared state atom; created if absent
     :workspace     — path to the saved workspace directory (optional)
     :podman        — map of podman CLI options, e.g. {:podman-cmd \"podman\",
                        :expose-ports [20000 21000]} (optional)
     :cleanup?      — delete job directories after completion (default false)"
  [{:keys [work-dir mailman state] :as conf}]
  (let [with-state (emi/with-state (or state (atom {})))
        wd      (or work-dir (str (fs/create-temp-dir)))
        job-ctx {}]
    (log/info "Creating podman container routes, work dir:" wd)
    [[:container/job-queued
      [{:handler      prepare-child-cmd
        :interceptors [emi/handle-job-error
                       (job-queued-result conf)
                       with-state
                       save-job
                       inc-job-count
                       (add-job-dir wd)
                       (restore-ws)
                       (emi/add-mailman mailman)
                       (assign-ports (get-in conf [:podman :expose-ports]
                                             default-expose-range))
                       (add-job-ctx job-ctx)
                       emi/start-process
                       (add-podman-opts (:podman conf))]}]]

     [:job/initializing
      [{:handler      job-init
        :interceptors [emi/handle-job-error
                       with-state
                       require-job
                       (add-job-dir wd)
                       (emi/add-mailman mailman)
                       watch-events]}]]

     [:container/pending
      [{:handler      job-pending
        :interceptors [with-state
                       require-job]}]]

     [:container/end
      [{:handler      job-exec
        :interceptors [with-state
                       require-job
                       remove-job
                       dec-job-count
                       emi/handle-job-error
                       (cleanup conf)
                       (add-job-dir wd)
                       (add-job-ctx job-ctx)
                       stop-watch-events
                       release-ports]}]]]))
