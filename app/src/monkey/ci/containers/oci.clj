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
             [config :as c]
             [containers :as mcc]
             [edn :as edn]
             [jobs :as j]
             [oci :as oci]
             [protocols :as p]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.config.sidecar :as cos]
            [monkey.ci.containers.promtail :as pt]
            [monkey.ci.events.core :as ec]
            [monkey.oci.container-instance.core :as ci]))

;; TODO Get this information from the OCI shapes endpoint
(def max-pod-memory "Max memory that can be assigned to a pod, in gbs" 64)
(def max-pod-cpus "Max number of cpu's to assign to a pod" 16)

(def work-dir oci/work-dir)
(def script-dir "/opt/monkeyci/script")
(def log-dir (oci/checkout-subdir "log"))
(def events-dir (oci/checkout-subdir "events"))
(def start-file (str events-dir "/start"))
(def abort-file (str events-dir "/abort"))
(def event-file (str events-dir "/events.edn"))
(def script-vol "scripts")
(def job-script "job.sh")
(def config-vol "config")
(def config-dir "/home/monkeyci/config")
(def job-config-file "job.edn")
(def job-container-name "job")
(def config-file "config.edn")

(def sidecar-container-name "sidecar")

(def promtail-config-vol "promtail-config")
(def promtail-config-dir "/etc/promtail")
(def promtail-config-file "config.yml")

;; Cpu architectures
(def arch-arm :arm)
(def arch-amd :amd)
(def valid-architectures #{arch-arm arch-amd})

(def arch-shapes
  "Architectures mapped to OCI shapes"
  {arch-arm
   {:shape "CI.Standard.A1.Flex"
    :credits 1}
   arch-amd
   {:shape "CI.Standard.E4.Flex"
    :credits 2}})
(def default-arch arch-arm)

(defn- job-arch [job]
  (get job :arch default-arch))

(defn- job-work-dir
  "The work dir to use for the job in the container.  This is the external job
   work dir, rebased onto the base work dir."
  [job build]
  (let [cd (b/checkout-dir build)]
    (log/debug "Determining job work dir using checkout dir" cd
               ", job dir" (j/work-dir job)
               "and base dir" (oci/base-work-dir build))
    (-> (or (j/work-dir job) cd)
        (u/rebase-path cd (oci/base-work-dir build)))))

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
                     "--events-file" event-file
                     "--start-file" start-file
                     "--abort-file" abort-file
                     "--job-config" (str config-dir "/" job-config-file)]
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
      (cos/set-build (select-keys build [:build-id :checkout-dir :sid :workspace]))
      (cos/set-job (-> job
                       (select-keys [:id :save-artifacts :restore-artifacts :caches :dependencies])
                       (assoc :work-dir (job-work-dir job build))))
      (cos/set-events-file event-file)
      (cos/set-start-file start-file)
      (cos/set-abort-file abort-file)
      (cos/set-api (get-in conf [:runtime :config :api]))))

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
  (assoc ic :shape (get-in arch-shapes [(job-arch job) :shape])))

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

(defn wait-for-instance-end-events
  "Checks the incoming events to see if a container and job end event has been received.
   Returns a deferred that will contain both events."
  [events sid job-id max-timeout]
  (letfn [(wait-for [t]
            ;; FIXME Seems like the timeout does not always work.
            (md/timeout!
             (ec/wait-for-event events
                                {:types #{t}
                                 :sid sid}
                                #(= job-id (get-in % [:job :id])))
             max-timeout (ec/set-result
                          {:type t}
                          (ec/make-result :error 1 (str "Timeout after " max-timeout " msecs")))))]
    ;; TODO As soon as a failure for the sidecar has been received, we should return
    ;; because then container events won't be sent anymore anyway.
    (md/zip
     (wait-for :container/end)
     (wait-for :sidecar/end))))

(defn wait-or-timeout
  "Waits for the container end event, or times out.  Afterwards, the full container
   instance details are fetched.  The exit codes in the received events are used for
   the container exit codes."
  [{:keys [events job build]} max-timeout get-details]
  (md/chain
   (wait-for-instance-end-events events
                                 (b/sid build)
                                 (j/job-id job)
                                 max-timeout)
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

(defn- credit-multiplier
  "Calculates the credit multiplier that needs to be applied for the job.  This 
   varies depending on the architecture, number of cpu's and amount of memory."
  [job]
  (+ (* (get job :cpus oci/default-cpu-count)     
        (get-in arch-shapes [(job-arch job) :credits] 1))
     (get job :memory oci/default-memory-gb)))

(defn run-container [{:keys [job] :as conf}]
  (log/debug "Running job as OCI instance:" job)
  (let [client (ci/make-context (:oci conf))
        ic (instance-config conf)
        max-job-timeout (* 20 60 1000)]
    (md/chain
     (oci/run-instance client ic
                       {:delete? true
                        :exited? (fn [id]
                                   ;; TODO When a start event has not been received after
                                   ;; a sufficient period of time, start polling anyway.
                                   ;; For now, we add a max timeout.
                                   (wait-or-timeout conf max-job-timeout
                                                    #(oci/get-full-instance-details client id)))})
     (fn [r]
       (letfn [(maybe-log-output [{:keys [exit-code display-name logs] :as c}]
                 (when (and (some? exit-code) (not= 0 exit-code))
                   (log/warn "Container" display-name "returned a nonzero exit code:" exit-code)
                   (log/warn "Captured output:" logs))
                 c)]
         (let [containers (->> (get-in r [:body :containers])
                               (mapv maybe-log-output))
               nonzero (->> containers
                            (filter (comp (complement (fnil zero? 0)) ec/result-exit))
                            (first))
               job-cont (->> containers
                             (filter (comp (partial = job-container-name) :display-name))
                             (first))]
           (log/debug "Containers:" containers)
           ;; Either return the first error result, or the result of the job container
           (:result (or nonzero job-cont))))))))

(defn- rt->container-config [rt]
  {:runtime rt ; TODO Get rid of the runtime
   :job (:job rt)
   :build (rt/build rt)
   :promtail (get-in rt [rt/config :promtail])
   :events (:events rt)
   :oci (:containers rt)})

;; Will be replaced with OciContainerRunner component
(defmethod mcc/run-container :oci [rt]
  (run-container (rt->container-config rt)))

(defmethod mcc/normalize-containers-config :oci [conf]
  ;; Take app version if no image version specified
  (update-in conf [:containers :image-tag] #(format (or % "%s") (c/version))))

(defmethod mcc/credit-multiplier-fn :oci [_]
  credit-multiplier)

(defrecord OciContainerRunner [conf]
  p/ContainerRunner
  (run-container [this job]
    (run-container (assoc conf :job job))))
