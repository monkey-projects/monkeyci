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
            [monkey.ci.events.core :as ec]
            [monkey.oci.container-instance.core :as ci]))

(def work-dir oci/work-dir)
(def script-dir "/opt/monkeyci/script")
(def log-dir (oci/checkout-subdir "log"))
(def start-file (str log-dir "/start"))
(def event-file (str log-dir "/events.edn"))
(def script-vol "scripts")
(def job-script "job.sh")
(def config-vol "config")
(def config-dir "/home/monkeyci/config")
(def job-config-file "job.edn")
(def job-container-name "job")

(def sidecar-config (comp :sidecar rt/config))

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
                              "MONKEYCI_EVENT_FILE" event-file})
     :working-directory wd}))

(defn- sidecar-container [{[c] :containers}]
  (assoc c
         :display-name "sidecar"
         :arguments ["sidecar"
                     "--events-file" event-file
                     "--start-file" start-file
                     "--job-config" (str config-dir "/" job-config-file)]
         ;; Run as root, because otherwise we can't write to the shared volumes
         :security-context {:security-context-type "LINUX"
                            :run-as-user 0}))

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
      (log/debug "  " script)))
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

(defn instance-config
  "Generates the configuration for the container instance.  It has 
   a container that runs the job, as configured in the `:job`, and
   next to that a sidecar that is responsible for capturing the output
   and dispatching events."
  [conf rt]
  (let [ic (oci/instance-config conf)
        sc (-> (sidecar-container ic)
               (update :volume-mounts conj (config-mount rt))
               (add-sidecar-env rt))
        jc (-> (job-container rt)
               ;; Use common volume for logs and events
               (assoc :volume-mounts [(oci/find-mount sc oci/checkout-vol)
                                      (script-mount rt)]))]
    (-> ic
        (assoc :containers [sc jc]
               :display-name (display-name rt))
        ;; Increase memory, sidecar also needs a lot to save caches
        ;; TODO Reduce this as soon as we manage to reduce sidecar memory usage
        (assoc-in [:shape-config :memory-in-g-bs] 4)
        (update :freeform-tags merge (oci/sid->tags (get-in rt [:build :sid])))
        (update :volumes conj
                (script-vol-config rt)
                (config-vol-config rt)))))

(defn wait-for-sidecar-end-event
  "Checks the incoming events to see if a sidecar end event has been received.
   Returns a deferred that will contain the sidecar end event."
  [events sid job-id]
  (ec/wait-for-event events
                     {:types #{:sidecar/end}
                      :sid sid}
                     #(= job-id (get-in % [:job :id]))))

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
                                   (md/chain
                                    ;; TODO When a start event has not been received after
                                    ;; a sufficient period of time, start polling anyway.
                                    ;; For now, we add a max timeout.
                                    ;; FIXME Seems like the timeout not always works.
                                    (md/timeout!
                                     (wait-for-sidecar-end-event (:events rt)
                                                                 (b/get-sid rt)
                                                                 (b/rt->job-id rt))
                                     max-job-timeout ::timeout)
                                    (fn [r]
                                      (log/debug "Job instance terminated, or timed out with value:" r)
                                      ;; FIXME If a job times out, it should fail
                                      (when (= r ::timeout)
                                        (log/warn "Container job timed out after" max-job-timeout "msecs"))
                                      (oci/get-full-instance-details client id))))})
     (fn [r]
       (letfn [(maybe-log-output [{:keys [exit-code display-name logs] :as c}]
                 (when (and (not (nil? exit-code)) (not= 0 exit-code))
                   (log/warn "Container" display-name "returned a nonzero exit code:" exit-code)
                   (log/warn "Captured output:" logs))
                 c)]
         (->> (get-in r [:body :containers])
              (mapv maybe-log-output)
              (map :exit-code)
              ;; It may occur that the sidecar/end event has already been received, but the
              ;; container has not stopped yet.  In that case, they will have a `nil` exit
              ;; code and we'll assume it's zero, since the event will already inform us of
              ;; any errors.
              (filter (complement (fnil zero? 0)))
              (first))))
     (fn [exit]
       ;; TODO Add more info on failure
       {:exit (or exit 0)}))))

(defmethod mcc/normalize-containers-config :oci [conf]
  (-> (oci/normalize-config conf :containers)
      ;; Take app version if no image version specified
      (update-in [:containers :image-tag] #(or % (c/version)))))
