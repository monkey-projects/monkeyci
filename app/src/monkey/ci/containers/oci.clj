(ns monkey.ci.containers.oci
  "Container runner implementation that uses OCI container instances."
  (:require [babashka.fs :as fs]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure
             [string :as cs]
             [walk :as cw]]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [containers :as mcc]
             [edn :as edn]
             [jobs :as j]
             [oci :as oci]
             [protocols :as p]
             [runtime :as rt]
             [time :as t]
             [utils :as u]
             [version :as v]]
            [monkey.ci.config.sidecar :as cos]
            [monkey.ci.containers.promtail :as pt]
            [monkey.ci.events.core :as ec]
            [monkey.ci.events.mailman.interceptors :as emi]
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
         :arguments ["-c" (str config-dir "/" config-file)
                     "sidecar"
                     ;; TODO Move this to config file
                     "--events-file" event-file
                     "--start-file" start-file
                     "--abort-file" abort-file]
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

(defn wait-for-startup
  "Waits until a container start event has been received.  This is the indication
   that user code is running, so we can send out a job/start event and register the
   job start time.  If a sidecar error is received before that, it means something
   is wrong."
  [events sid job-id]
  (letfn [(container-started [evt]
            (log/debug "Detected container start:" job-id)
            (md/chain
             (ec/post-events events [(j/job-start-evt job-id sid)])
             (constantly evt)))
          (sidecar-failed [evt]
            (let [now (t/now)]
              (log/warn "Detected sidecar failure for job" job-id)
              (md/chain
               (ec/post-events events [(j/job-executed-evt (:job-id evt)
                                                           sid
                                                           (-> (select-keys evt [:message :exception])
                                                               (assoc :status :error)))])
               (constantly (md/error-deferred (ex-info "Sidecar failed to start" evt))))))]
    (log/debug "Waiting for container startup:" job-id)
    (-> (ec/wait-for-event events
                           {:types #{:container/start :sidecar/end}
                            :sid sid}
                           (comp (partial = job-id) :job-id))
        (md/timeout! container-start-timeout)
        (md/chain
         (fn [evt]
           (condp = (:type evt)
             :container/start
             (container-started evt)
             :sidecar/end
             (sidecar-failed evt)))))))

(defn wait-for-instance-end-events
  "Checks the incoming events to see if a container and job end event has been received.
   Returns a deferred that will contain both events, or that times out after `max-timeout`."
  [events sid job-id max-timeout]
  (log/debug "Waiting for job containers to terminate:" job-id)
  (letfn [(wait-for [t]
            ;; FIXME Seems like the timeout does not always work.
            (md/timeout!
             (ec/wait-for-event events
                                {:types #{t}
                                 :sid sid}
                                (comp (partial = job-id) :job-id))
             max-timeout
             (ec/set-result
              {:type t}
              (ec/make-result :error 1 (str "Timeout after " max-timeout " msecs")))))]
    ;; TODO As soon as a failure for the sidecar has been received, we should return
    ;; because then container events won't be sent anymore anyway.
    (md/zip
     (wait-for :container/end)
     (wait-for :sidecar/end))))

(defn wait-for-results
  "Waits for the container end event, or times out.  Afterwards, the full container
   instance details are fetched.  The exit codes in the received events are used for
   the container exit codes."
  [{:keys [events job build]} max-timeout get-details]
  (md/chain
   (wait-for-startup events (b/sid build) (j/job-id job))
   (fn [_]
     (wait-for-instance-end-events events
                                   (b/sid build)
                                   (j/job-id job)
                                   max-timeout))
   (fn [r]
     (log/debug "Job instance terminated with these events:" r)
     (md/zip r (get-details)))
   (fn [[events details]]
     ;; Take exit codes from events, add them to container details
     (let [by-type (group-by :type events)
           container-types {sidecar-container-name :sidecar/end
                            job-container-name :container/end}
           add-exit (fn [{:keys [display-name] :as c}]
                      (if-let [evt (first (get by-type (get container-types display-name)))]
                        (let [exit-code (or (ec/result-exit evt) (:exit-code c) 0)]
                          ;; Add the full result, may be useful for higher levels
                          (assoc c :result (assoc (ec/result evt) :exit exit-code)))
                        (do
                          (log/debug "Unknown container:" display-name ", ignoring")
                          c)))]
       ;; We don't rely on the container exit codes, since the container may not have
       ;; exited completely when the events have arrived, so we use the event values instead.
       (update-in details [:body :containers] (partial map add-exit))))))

(defn- job-credit-multiplier
  "Calculates the credit multiplier that needs to be applied for the job.  This 
   varies depending on the architecture, number of cpu's and amount of memory and
   is taken from the actual instance configuration."
  [job]
  (oci/credit-multiplier (job-arch job)
                         (get job :cpus oci/default-cpu-count)
                         (get job :memory oci/default-memory-gb)))

(defn- fire-job-initializing [job build-sid events ic]
  (ec/post-events events [(j/job-initializing-evt (j/job-id job) build-sid (oci/credit-multiplier ic))]))

(defn- fire-job-executed [job-id build-sid result events]
  (let [result (-> result
                   (assoc :status (b/exit-code->status (:exit result))))]
    (ec/post-events events [(j/job-executed-evt job-id build-sid result)])))

(defn- fire-job-error [{:keys [events job build]} ex]
  (log/error "Got error:" ex)
  (fire-job-executed (j/job-id job)
                     (b/sid build)
                     (j/ex->result (or (:exception ex) ex))
                     events)
  nil)

(defn- validate-job! [{:keys [job] :as conf}]
  ;; TODO Add more validations
  (when (some nil? (-> job mcc/env vals))
    (fire-job-error
     conf
     (ex-info "Invalid job configuration: environment variables must have a value"
              {:job job})))
  true)

(defn ^:deprecated run-container [{:keys [events job build] :as conf}]
  (log/debug "Running job as OCI instance:" job)
  (log/debug "Build details:" build)
  (let [client (-> (ci/make-context (:oci conf))
                   (oci/add-inv-interceptor :containers))
        ic     (instance-config conf)]
    (fire-job-initializing job (b/sid build) events ic)
    (when (validate-job! conf)
      (try 
        (-> (oci/run-instance client ic
                              {:delete? true
                               :exited? (fn [id]
                                          ;; TODO When a start event has not been received after
                                          ;; a sufficient period of time, start polling anyway.
                                          ;; For now, we add a max timeout.
                                          (wait-for-results conf j/max-job-timeout
                                                            #(oci/get-full-instance-details client id)))})
            (md/chain
             (fn [r]
               (letfn [(maybe-log-output [{:keys [exit-code display-name logs] :as c}]
                         (when (and (some? exit-code) (not= 0 exit-code))
                           (log/warn "Container" display-name "returned a nonzero exit code:" exit-code)
                           (log/warn "Captured output:" logs))
                         c)]
                 (let [containers (->> (get-in r [:body :containers])
                                       (mapv maybe-log-output))
                       nonzero    (->> containers
                                       (filter (comp (complement (fnil zero? 0)) ec/result-exit))
                                       (first))
                       job-cont   (->> containers
                                       (filter (comp (partial = job-container-name) :display-name))
                                       (first))]
                   (log/debug "Containers:" containers)
                   ;; Either return the first error result, or the result of the job container
                   (if-let [res (:result (or nonzero job-cont))]
                     (md/success-deferred res)
                     ;; Result does not contain container info, so error
                     (md/error-deferred r)))))
             (fn [r]
               (md/chain
                (fire-job-executed (j/job-id job) (b/sid build) r events)
                (constantly r))))
            (md/catch (partial fire-job-error conf)))
        (catch Exception ex
          (fire-job-error conf ex))))))

(defrecord OciContainerRunner [conf events credit-consumer]
  p/ContainerRunner
  (run-container [this job]
    (run-container (assoc conf
                          :events events
                          :job job))))

(defn make-container-runner [conf events]
  (->OciContainerRunner conf events job-credit-multiplier))

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

(def container-ended? (comp true? ::container-end))

(def sidecar-ended? (comp true? ::sidecar-end))

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

(def set-container-end
  {:name ::set-container-end
   :enter (fn [ctx]
            (assoc ctx ::container-end true))})

(def set-sidecar-end
  {:name ::set-sidecar-end
   :enter (fn [ctx]
            (assoc ctx ::sidecar-end true))})

;;; Event handlers

(defn job-queued [ctx]
  [(j/job-initializing-evt (job-id ctx) (build-sid ctx) (get-credit-multiplier ctx))])

(defn container-start
  "Indicates the container script has started.  This means the job is actually running."
  [ctx]
  [(j/job-start-evt (job-id ctx) (build-sid ctx))])

(defn- job-executed-evt [{:keys [event] :as ctx}]
  (j/job-executed-evt (job-id ctx) (build-sid ctx) (assoc (:result event) :status (:status event))))

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
        state (emi/with-state (atom {:build build
                                     :config conf}))]
    [[:container/job-queued
      ;; Job picked up, start the container instance
      [{:handler job-queued
        :interceptors [emi/handle-job-error
                       (add-config conf)
                       prepare-instance-config
                       (oci/start-ci-interceptor client)]}]]

     ;; Container script has started along with the sidecar (which forwards events)
     [:container/start
      [{:handler container-start}]]
     
     ;; Container script has ended, all commands executed
     [:container/end
      [{:handler container-end
        :interceptors [set-container-end]}]]
     
     ;; Sidecar terminated, artifacts have been stored
     [:sidecar/end
      [{:handler sidecar-end
        :interceptors [set-sidecar-end]}]]]))
