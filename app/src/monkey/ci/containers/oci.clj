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
             [oci :as oci]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.containers.promtail :as pt]
            [monkey.ci.events.core :as ec]
            [monkey.oci.container-instance.core :as ci]))

(def max-pod-memory "Max memory that can be assigned to a pod, in gbs" 64)
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

(def sidecar-container-name "sidecar")
(def sidecar-config (comp :sidecar rt/config))

(def promtail-config-vol "promtail-config")
(def promtail-config-dir "/etc/promtail")
(def promtail-config-file "config.yml")

(defn- job-work-dir
  "The work dir to use for the job in the container.  This is the external job
   work dir, rebased onto the base work dir."
  [rt]
  (let [cd (b/rt->checkout-dir rt)]
    (log/debug "Determining job work dir using checkout dir" cd
               ", job dir" (get-in rt [:job :work-dir])
               "and base dir" (oci/base-work-dir rt))
    (-> (get-in rt [:job :work-dir] cd)
        (u/rebase-path cd (oci/base-work-dir rt)))))

(defn- job-container
  "Configures the job container.  It runs the image as configured in
   the job, but the script that's being executed is replaced by a
   custom shell script that also redirects the output and dispatches
   events to a file, that are then picked up by the sidecar."
  [{:keys [job] :as rt}]
  (let [wd (job-work-dir rt)]
    {:image-url (or (:image job) (:container/image job))
     :display-name job-container-name
     ;; Override entrytpoint
     :command ["/bin/sh" (str script-dir "/" job-script)]
     ;; One file arg per script line, with index as name
     :arguments (->> (count (:script job))
                     (range)
                     (mapv str))
     :environment-variables (merge
                             (:container/env job)
                             {"MONKEYCI_WORK_DIR" wd
                              "MONKEYCI_LOG_DIR" log-dir
                              "MONKEYCI_SCRIPT_DIR" script-dir
                              "MONKEYCI_START_FILE" start-file
                              "MONKEYCI_ABORT_FILE" abort-file
                              "MONKEYCI_EVENT_FILE" event-file})
     :working-directory wd}))

(defn- sidecar-container [{[c] :containers}]
  (assoc c
         :display-name sidecar-container-name
         :arguments ["sidecar"
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
  [ic rt]
  (let [conf (pt/rt->config rt)
        add? (some? (:loki-url conf))]
    (cond-> ic
      add?
      (-> (update :containers conj (-> (promtail-container conf)
                                       (assoc :volume-mounts [(find-checkout-vol ic)
                                                              (promtail-config-mount)])))
          (update :volumes conj (promtail-config-vol-config conf))))))

(defn- display-name [{:keys [build job]}]
  (cs/join "-" [(:build-id build)
                (bc/job-id job)]))

(defn- script-mount [{{:keys [script]} :job}]
  {:volume-name script-vol
   :is-read-only false
   :mount-path script-dir})

(defn- config-mount [_]
  {:volume-name config-vol
   :is-read-only true
   :mount-path config-dir})

(defn- job-script-entry []
  (oci/config-entry job-script (slurp (io/resource job-script))))

(defn- script-vol-config
  "Adds the job script and a file for each script line as a configmap volume."
  [{{:keys [script]} :job}]
  (when (log/enabled? :debug)
    (log/debug "Executing script lines in container:")
    (doseq [l script]
      (log/debug "  " l)))
  ;; TODO Also handle :container/cmd key
  {:name script-vol
   :volume-type "CONFIGFILE"
   :configs (->> script
                 (map-indexed (fn [i s]
                                (oci/config-entry (str i) s)))
                 (into [(job-script-entry)]))})

(defn- job-details->edn [rt]
  (pr-str {:job (-> (:job rt)
                     (select-keys [:id :save-artifacts :restore-artifacts :caches :dependencies])
                     (assoc :work-dir (job-work-dir rt)))}))

(defn- config-vol-config
  "Configuration files for the sidecar (e.g. logging)"
  [rt]
  (let [{:keys [log-config]} (sidecar-config rt)]
    {:name config-vol
     :volume-type "CONFIGFILE"
     :configs (cond-> [(oci/config-entry job-config-file (job-details->edn rt))]
                log-config (conj (oci/config-entry "logback.xml" log-config)))}))

(defn- add-sidecar-env [sc rt]
  (let [wd (oci/base-work-dir rt)]
    ;; TODO Put this all in a config file instead, this way sensitive information is harder to see
    (assoc sc
           :environment-variables
           (-> (rt/rt->env rt)
               ;; Remove some unnecessary values
               (dissoc :args :checkout-base-dir :jwk :containers :storage) 
               (update :build dissoc :git :jobs :cleanup?)
               (assoc :work-dir wd
                      :checkout-base-dir work-dir)
               (assoc-in [:build :checkout-dir] wd)
               (c/config->env)
               (as-> x (mc/map-keys (comp csk/->SCREAMING_SNAKE_CASE name) x))))))

(defn- set-pod-resources [ic rt]
  (-> ic
      (update :shape-config mc/assoc-some :memory-in-g-bs (get-in rt [:job :memory]))
      (mc/update-existing-in [:shape-config :memory-in-g-bs] min max-pod-memory)))

(defn instance-config
  "Generates the configuration for the container instance.  It has 
   a container that runs the job, as configured in the `:job`, and
   next to that a sidecar that is responsible for capturing the output
   and dispatching events.  If configured, it also "
  [conf rt]
  (let [ic (oci/instance-config conf)
        sc (-> (sidecar-container ic)
               (update :volume-mounts conj (config-mount rt))
               (add-sidecar-env rt))
        jc (-> (job-container rt)
               ;; Use common volume for logs and events
               (assoc :volume-mounts [(find-checkout-vol ic)
                                      (script-mount rt)]))]
    (-> ic
        (assoc :containers [sc jc]
               :display-name (display-name rt))
        (update :freeform-tags merge (oci/sid->tags (get-in rt [:build :sid])))
        (update :volumes conj
                (script-vol-config rt)
                (config-vol-config rt))
        (set-pod-resources rt)
        ;; Note that promtail will never terminate, so we rely on the script to
        ;; delete the container instance when the sidecar and the job have completed.
        (add-promtail-container rt))))

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
    (md/zip
     (wait-for :container/end)
     (wait-for :sidecar/end))))

(defn wait-or-timeout
  "Waits for the container end event, or times out.  Afterwards, the full container
   instance details are fetched.  The exit codes in the received events are used for
   the container exit codes."
  [rt max-timeout get-details]
  (md/chain
   (wait-for-instance-end-events (:events rt)
                                 (b/get-sid rt)
                                 (b/rt->job-id rt)
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

(defmethod mcc/run-container :oci [rt]
  (log/debug "Running job as OCI instance:" (:job rt))
  (let [conf (:containers rt)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))
        ic (instance-config conf rt)
        max-job-timeout (* 20 60 1000)]
    (md/chain
     (oci/run-instance client ic
                       {:delete? true
                        :exited? (fn [id]
                                   ;; TODO When a start event has not been received after
                                   ;; a sufficient period of time, start polling anyway.
                                   ;; For now, we add a max timeout.
                                   (wait-or-timeout rt max-job-timeout
                                                    #(oci/get-full-instance-details client id)))})
     (fn [r]
       (letfn [(maybe-log-output [{:keys [exit-code display-name logs] :as c}]
                 (when (and (not (nil? exit-code)) (not= 0 exit-code))
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

(defmethod mcc/normalize-containers-config :oci [conf]
  (-> (oci/normalize-config conf :containers)
      ;; Take app version if no image version specified
      (update-in [:containers :image-tag] #(or % (c/version)))))
