(ns monkey.ci.containers.oci
  "Container runner implementation that uses OCI container instances."
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [containers :as mcc]
             [oci :as oci]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]))

(defn- subdir [n]
  (str oci/checkout-dir "/" n))

(def work-dir (subdir "work"))
(def script-dir "/opt/monkeyci/script")
(def log-dir (subdir "log"))
(def start-file (str log-dir "/start"))
(def event-file (str log-dir "/events.edn"))
(def script-vol "scripts")
(def job-script "job.sh")
(def config-vol "config")
(def config-dir "/home/monkeyci/config")

(defn- job-container
  "Configures the job container.  It runs the image as configured in
   the step, but the script that's being executed is replaced by a
   custom shell script that also redirects the output and dispatches
   events to a file, that are then picked up by the sidecar."
  [{:keys [step]}]
  {:image-url (:container/image step)
   :display-name "job"
   :command ["/bin/sh" (str script-dir "/" job-script)]
   ;; One file arg per script line, with index as name
   :arguments (->> (count (:script step))
                   (range)
                   (mapv str))
   :environment-variables {"WORK_DIR" work-dir
                           "LOG_DIR" log-dir
                           "SCRIPT_DIR" script-dir
                           "START_FILE" start-file
                           "EVENT_FILE" event-file}})

(defn- sidecar-container [{[c] :containers}]
  (assoc c
         :display-name "sidecar"
         :command ["sidecar"]
         :arguments ["--events-file" event-file
                     "--start-file" start-file]))

(defn- display-name [{:keys [build step]}]
  (cs/join "-" [(:build-id build)
                (:pipeline step)
                (:index step)]))

(defn- script-mount [{{:keys [script]} :step}]
  {:volume-name script-vol
   :is-read-only false
   :mount-path script-dir})

(defn- config-mount [_]
  {:volume-name config-vol
   :is-read-only true
   :mount-path config-dir})

(defn- config-entry [n v]
  {:file-name n
   :data (u/->base64 v)})

(defn- job-script-entry []
  (config-entry job-script (slurp (io/resource job-script))))

(defn- script-vol-config
  "Adds the job script and a file for each script line as a configmap volume."
  [{{:keys [script]} :step}]
  {:name script-vol
   :volume-type "CONFIGFILE"
   :configs (->> script
                 (map-indexed (fn [i s]
                                (config-entry (str i) s)))
                 (into [(job-script-entry)]))})

(defn- config-vol-config
  "Configuration files for the sidecar (e.g. logging)"
  [{{:keys [log-config]} :sidecar}]
  {:name config-vol
   :volume-type "CONFIGFILE"
   :configs (cond-> []
              log-config (conj (config-entry "logback.xml" (slurp log-config))))})

(defn instance-config
  "Generates the configuration for the container instance.  It has 
   a container that runs the job, as configured in the `:step`, and
   next to that a sidecar that is responsible for capturing the output
   and dispatching events."
  [conf ctx]
  (let [ic (oci/instance-config conf)
        sc (-> (sidecar-container ic)
               (update :volume-mounts conj (config-mount ctx)))
        jc (-> (job-container ctx)
               ;; Use common volume for logs and events
               (assoc :volume-mounts [(oci/find-mount sc oci/checkout-vol)
                                      (script-mount ctx)]))]
    (-> ic
        (assoc :containers [sc jc]
               :display-name (display-name ctx)
               :tags (oci/sid->tags (get-in ctx [:build :sid])))
        (update :volumes conj
                (script-vol-config ctx)
                (config-vol-config ctx)))))

(defmethod mcc/run-container :oci [ctx]
  ;; TODO
  ;; - (Make sure the exctracted code, caches and artifacts are uploaded to storage)
  ;; - Generate and upload script files to execute
  ;; - Build the config struct that includes the sidecar
  ;; - Use common volume for logs and events
  ;; - Create a container instance
  ;; - Wait for it to finish
  ;; - Clean up temp files from storage
  (let [conf (-> ctx
                 (oci/ctx->oci-config :container)
                 (oci/->oci-config))
        client (ci/make-context conf)
        ic (instance-config conf ctx)]
    (-> (oci/run-instance client ic)
        (md/chain (partial hash-map :exit))
        (deref))))
