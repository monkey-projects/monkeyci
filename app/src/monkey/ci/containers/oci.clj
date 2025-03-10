(ns monkey.ci.containers.oci
  "Container runner implementation that uses OCI container instances."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [containers :as mcc]
             [edn :as edn]
             [jobs :as j]
             [oci :as oci]
             [utils :as u]]
            [monkey.ci.containers.promtail :as pt]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.sidecar.config :as cos]
            [monkey.oci.container-instance.core :as ci]))

;; TODO Get this information from the OCI shapes endpoint
(def max-pod-memory "Max memory that can be assigned to a pod, in gbs" 64)
(def max-pod-cpus "Max number of cpu's to assign to a pod" 16)

(def work-dir oci/work-dir)
(def script-dir oci/script-dir)
(def log-dir (oci/checkout-subdir "log"))
(def events-dir (oci/checkout-subdir "events"))
(def start-file (str events-dir "/start"))
(def abort-file (str events-dir "/abort"))
(def event-file (str events-dir "/events.edn"))
(def script-vol "scripts")
(def job-script "job.sh")
(def config-vol "config")
(def config-dir "/home/monkeyci/config")
(def job-container-name "job")
(def config-file "config.edn")

(def sidecar-container-name "sidecar")

(def promtail-config-vol "promtail-config")
(def promtail-config-dir "/etc/promtail")
(def promtail-config-file "config.yml")

(defn- job-arch [job]
  (get job :arch))

(defn- checkout-dir
  "Checkout dir for the build in the job container"
  [build]
  (u/combine work-dir (fs/file-name (b/checkout-dir build))))

(defn- job-work-dir
  "The work dir to use for the job in the container.  This is the external job
   work dir, relative to the container checkout dir."
  [job build]
  (let [wd (j/work-dir job)]
    (cond-> (checkout-dir build)
      wd (u/combine wd))))

(defn- job-container
  "Configures the job container.  It runs the image as configured in
   the job, but the script that's being executed is replaced by a
   custom shell script that also redirects the output and dispatches
   events to a file, that are then picked up by the sidecar."
  [job build]
  (let [wd (job-work-dir job build)]
    (cond-> {:image-url (mcc/image job)
             :display-name job-container-name
             ;; In container instances, command or entrypoint are treated the same
             ;; Note that when using container commands directly, the job will most
             ;; likely start without the workspace being restored.  This is a limitation
             ;; of container instances, that don't allow init containers or mounting
             ;; pre-populated file systems (apart from configmaps).  Also capturing
             ;; logs is problematic, since we can't redirect to a file.
             :command (or (mcc/cmd job)
                          (mcc/entrypoint job))
             :arguments (mcc/args job)
             :environment-variables (mcc/env job)
             :working-directory wd}
      ;; Override some props if script is specified
      (:script job) (-> (assoc :command [(get job :shell "/bin/sh") (str script-dir "/" job-script)]
                               ;; One file arg per script line, with index as name
                               :arguments (->> (count (:script job))
                                               (range)
                                               (mapv str)))
                        (update :environment-variables
                                merge
                                {"MONKEYCI_WORK_DIR" wd
                                 "MONKEYCI_LOG_DIR" log-dir
                                 "MONKEYCI_SCRIPT_DIR" script-dir
                                 "MONKEYCI_START_FILE" start-file
                                 "MONKEYCI_ABORT_FILE" abort-file
                                 "MONKEYCI_EVENT_FILE" event-file})))))

(defn- sidecar-container [{[c] :containers}]
  (assoc c
         :display-name sidecar-container-name
         :command (oci/make-cmd
                   "-c" (str config-dir "/" config-file)
                   "internal"
                   "sidecar"
                   ;; TODO Move this to config file
                   "--events-file" event-file
                   "--start-file" start-file
                   "--abort-file" abort-file)
         ;; Run as root, because otherwise we can't write to the shared volumes
         :security-context {:security-context-type "LINUX"
                            :run-as-user 0}))

(defn- find-checkout-vol [ci]
  (-> ci
      :containers
      first
      (oci/find-mount oci/checkout-vol)))

(defn- promtail-container [conf]
  (-> conf
      (pt/promtail-container)
      (assoc :arguments ["-config.file" (str promtail-config-dir "/" promtail-config-file)])))

(defn- promtail-config-mount []
  {:volume-name promtail-config-vol
   :is-read-only true
   :mount-path "/etc/promtail"})

(defn- promtail-config-vol-config [conf]
  (let [conf (-> conf
                 (assoc :paths [(str log-dir "/*.log")]))]
    {:name promtail-config-vol
     :volume-type "CONFIGFILE"
     :configs [(oci/config-entry promtail-config-file
                                 (pt/yaml-config conf))]}))

(defn- add-promtail-container
  "Adds promtail container configuration to the existing instance config.
   It will be configured to push all logs from the script log dir."
  [ic {:keys [promtail job build]}]
  (let [conf (pt/make-config promtail job build)
        add? (some? (:loki-url conf))]
    (cond-> ic
      add?
      (-> (update :containers conj (-> (promtail-container conf)
                                       (assoc :volume-mounts [(find-checkout-vol ic)
                                                              (promtail-config-mount)])))
          (update :volumes conj (promtail-config-vol-config conf))))))

(defn- display-name [job build]
  (cs/join "-" [(:build-id build)
                (j/job-id job)]))

(defn- script-mount [job]
  (when (:script job)
    {:volume-name script-vol
     :is-read-only false
     :mount-path script-dir}))

(defn- config-mount []
  {:volume-name config-vol
   :is-read-only true
   :mount-path config-dir})

(defn- job-script-entry []
  (oci/config-entry job-script (slurp (io/resource job-script))))

(defn- script-vol-config
  "Adds the job script and a file for each script line as a configmap volume."
  [{:keys [script]}]
  (when-not (empty? script)
    (when (log/enabled? :debug)
      (log/debug "Executing script lines in container:")
      (doseq [l script]
        (log/debug "  " l)))
    {:name script-vol
     :volume-type "CONFIGFILE"
     :configs (->> script
                   (map-indexed (fn [i s]
                                  (oci/config-entry (str i) s)))
                   (into [(job-script-entry)]))}))

(defn- make-sidecar-config
  "Creates a configuration map using the runtime, that can then be passed on to the
   sidecar container."
  [{:keys [build job] :as conf}]
  (-> {}
      (cos/set-build (-> build
                         (select-keys [:build-id :sid :workspace])
                         (assoc :checkout-dir (checkout-dir build))))
      (cos/set-job (-> job
                       (select-keys [:id :save-artifacts :restore-artifacts :caches :dependencies])
                       (assoc :work-dir (job-work-dir job build))))
      (cos/set-events-file event-file)
      (cos/set-start-file start-file)
      (cos/set-abort-file abort-file)
      (cos/set-api (:api conf))))

(defn- rt->edn [conf]
  (edn/->edn (make-sidecar-config conf)))

(defn- config-vol-config
  "Configuration files for the sidecar (e.g. logging)"
  [{:keys [job build sidecar] :as conf}]
  (let [{:keys [log-config]} sidecar]
    {:name config-vol
     :volume-type "CONFIGFILE"
     :configs (cond-> [(oci/config-entry config-file (rt->edn conf))]
                log-config (conj (oci/config-entry "logback.xml" log-config)))}))

(defn- set-pod-shape [ic job]
  (mc/assoc-some ic :shape (when-let [a (job-arch job)]
                             (get-in oci/arch-shapes [a :shape]))))

(defn- set-pod-memory [ic job]
  (-> ic
      (update :shape-config mc/assoc-some :memory-in-g-bs (:memory job))
      (mc/update-existing-in [:shape-config :memory-in-g-bs] min max-pod-memory)))

(defn- set-pod-cpus [ic job]
  (-> ic
      (update :shape-config mc/assoc-some :ocpus (:cpus job))
      (mc/update-existing-in [:shape-config :ocpus] min max-pod-cpus)))

(defn- set-pod-resources [ic job]
  (-> ic
      (set-pod-memory job)
      (set-pod-cpus job)))

(defn instance-config
  "Generates the configuration for the container instance.  It has 
   a container that runs the job, as configured in the `:job`, and
   next to that a sidecar that is responsible for capturing the output
   and dispatching events.  If configured, it also "
  [{:keys [job build oci] :as conf}]
  (log/debug "Setting up container instance for job using config:" conf)
  (let [ic (oci/instance-config oci)
        sc (-> (sidecar-container ic)
               (update :volume-mounts conj (config-mount)))
        jc (-> (job-container job build)
               ;; Use common volume for logs and events
               (assoc :volume-mounts (filter some? [(find-checkout-vol ic)
                                                    (script-mount job)])))]
    (-> ic
        (assoc :containers [sc jc]
               :display-name (display-name job build))
        (update :freeform-tags merge (oci/sid->tags (b/sid build)))
        (update :volumes concat
                (filter some? [(script-vol-config job)
                               (config-vol-config conf)]))
        (set-pod-shape job)
        (set-pod-resources job)
        ;; Note that promtail will never terminate, so we rely on the script to
        ;; delete the container instance when the sidecar and the job have completed.
        (add-promtail-container conf))))

;;; Container runner implementation

(def container-start-timeout
  "Max msecs to wait until container has started"
  (* 5 60 1000))

;;; Mailman events

;;; Context accessors

(def job-id (comp :job-id :event))
(def build-sid (comp :sid :event))

(def get-credit-multiplier ::credit-multiplier)

(defn set-credit-multiplier [ctx cm]
  (assoc ctx ::credit-multiplier cm))

(def get-build (comp :build emi/get-state))

(defn set-build [ctx b]
  (emi/update-state ctx assoc :build b))

(def get-config ::config)

(defn set-config [ctx c]
  (assoc ctx ::config c))

(defn- get-job-state
  "Retrieves state for the job indicated by `job-id` in the event."
  [ctx]
  (-> (emi/get-state ctx)
      (get-in [::jobs (job-id ctx)])))

(defn- job-state
  "Applies `f` to job state.  Does not update state."
  [ctx f]
  (f (get-job-state ctx)))

(defn- update-job-state
  "Applies `f` to job state, updates state."
  [ctx f & args]
  (apply emi/update-state ctx update-in [::jobs (job-id ctx)] f args))

(defn get-container-status [ctx]
  (job-state ctx :container-status))

(def container-ended? 
  (comp some? get-container-status))

(defn sidecar-ended? [ctx]
  (job-state ctx (comp some? :sidecar-status)))

(defn get-instance-id [ctx]
  (job-state ctx :instance-id))

;;; Interceptors

(defn add-config [conf]
  {:name ::add-config
   :enter (fn [ctx]
            (set-config ctx conf))})

(def prepare-instance-config
  {:name ::prepare-instance
   :enter (fn [ctx]
            (-> (get-config ctx)
                (assoc :build (get-build ctx)
                       :job (get-in ctx [:event :job]))
                (instance-config)
                (as-> c (oci/set-ci-config ctx c))))})

(def calc-credit-multiplier
  "Calculates credit multiplier according to instance config"
  {:name ::calc-credit-multiplier
   :enter (fn [ctx]
            (set-credit-multiplier ctx (oci/credit-multiplier (oci/get-ci-config ctx))))})

(def set-container-status
  {:name ::set-container-status
   :enter (fn [ctx]
            ;; FIXME container event is inconsistent, status should be in event, not in result
            (update-job-state ctx assoc :container-status (get-in ctx [:event :result :status])))})

(def set-sidecar-status
  {:name ::set-sidecar-status
   :enter (fn [ctx]
            ;; FIXME Status field is also in this event inconsistent
            (update-job-state ctx assoc :sidecar-status (get-in ctx [:event :result :status])))})

(def save-instance-id
  "Stores instance id taken from the instance creation response in the job state."
  {:name ::save-instance-id
   :enter (fn [ctx]
            (update-job-state ctx assoc :instance-id (-> ctx (oci/get-ci-response) :body :id)))})

(def load-instance-id
  {:name ::load-instance-id
   :enter (fn [ctx]
            (oci/set-ci-id ctx (get-instance-id ctx)))})

(def filter-container-job
  "Terminates processing when there is no instance id in the state, so it's not a container job."
  (emi/terminate-when ::filter-container-job (comp nil? get-instance-id)))

;;; Event handlers

(defn job-queued [ctx]
  [(j/job-initializing-evt (job-id ctx) (build-sid ctx) (get-credit-multiplier ctx))])

(defn container-start
  "Indicates the container script has started.  This means the job is actually running,
   since events are proliferated by the sidecar, so if events arrive, we're sure that
   the sidecar is running."
  [ctx]
  [(j/job-start-evt (job-id ctx) (build-sid ctx))])

(defn- job-executed-evt [{:keys [event] :as ctx}]
  (let [status (or (get-container-status ctx)
                   ;; Normally this is taken care of by interceptor, but it's more resilient
                   (get-in ctx [:event :result :status]))]
    (j/job-executed-evt (job-id ctx) (build-sid ctx) (assoc (:result event) :status status))))

(defn container-end
  "Indicates job script has terminated.  If the sidecar has also ended, the job has been
   executed"
  [ctx]
  (when (sidecar-ended? ctx)
    [(job-executed-evt ctx)]))

(defn sidecar-end
  "Indicates job sidecar has terminated.  If the container script has also ended, the job
   has been executed"
  [ctx]
  (when (container-ended? ctx)
    [(job-executed-evt ctx)]))

;;; Route configuration

(defn- make-ci-context [conf]
  (-> (ci/make-context (:oci conf))
      (oci/add-inv-interceptor :containers)))

(defn make-routes [conf build]
  (let [client (make-ci-context conf)
        state (emi/with-state (atom {:build build}))]
    [[:container/job-queued
      ;; Job picked up, start the container instance
      [{:handler job-queued
        :interceptors [emi/handle-job-error
                       state
                       (add-config conf)
                       prepare-instance-config
                       calc-credit-multiplier
                       (oci/start-ci-interceptor client)
                       save-instance-id]}]]

     ;; Container script has started along with the sidecar (which forwards events)
     [:container/start
      [{:handler container-start}]]
     
     ;; Container script has ended, all commands executed
     [:container/end
      [{:handler container-end
        :interceptors [state
                       set-container-status]}]]
     
     ;; Sidecar terminated, artifacts have been stored
     [:sidecar/end
      [{:handler sidecar-end
        :interceptors [state
                       set-sidecar-status]}]]

     ;; Job executed, we can delete the container instance
     [:job/executed
      [{:handler (constantly nil)
        :interceptors [state
                       load-instance-id
                       filter-container-job
                       (oci/delete-ci-interceptor client)]}]]]))
