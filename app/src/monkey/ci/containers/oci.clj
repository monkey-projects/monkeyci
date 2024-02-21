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
             [config :as c]
             [containers :as mcc]
             [oci :as oci]
             [runtime :as rt]
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
(def step-config-file "step.edn")
(def job-container-name "job")

(def sidecar-config (comp :sidecar rt/config))

(defn- calc-work-dir [rt]
  (some->> (get-in rt [:build :checkout-dir])
           (fs/file-name)
           (fs/path work-dir)
           (str)))

(defn- job-container
  "Configures the job container.  It runs the image as configured in
   the step, but the script that's being executed is replaced by a
   custom shell script that also redirects the output and dispatches
   events to a file, that are then picked up by the sidecar."
  [{:keys [step] :as rt}]
  {:image-url (:container/image step)
   :display-name job-container-name
   :command ["/bin/sh" (str script-dir "/" job-script)]
   ;; One file arg per script line, with index as name
   :arguments (->> (count (:script step))
                   (range)
                   (mapv str))
   :environment-variables {"WORK_DIR" (calc-work-dir rt)
                           "LOG_DIR" log-dir
                           "SCRIPT_DIR" script-dir
                           "START_FILE" start-file
                           "EVENT_FILE" event-file}})

(defn- sidecar-container [{[c] :containers}]
  (assoc c
         :display-name "sidecar"
         :arguments ["sidecar"
                     "--events-file" event-file
                     "--start-file" start-file
                     "--step-config" (str config-dir "/" step-config-file)]
         ;; Run as root, because otherwise we can't write to the shared volumes
         :security-context {:security-context-type "LINUX"
                            :run-as-user 0}))

(defn- display-name [{:keys [build step pipeline]}]
  (cs/join "-" [(:build-id build)
                (:index pipeline)
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

(defn- step-details->edn [rt]
  (pr-str {:step (select-keys (:step rt) [:name :index :save-artifacts :restore-artifacts :caches])
           :pipeline (select-keys (:pipeline rt) [:name :index])}))

(defn- config-vol-config
  "Configuration files for the sidecar (e.g. logging)"
  [rt]
  (let [{:keys [log-config]} (sidecar-config rt)]
    {:name config-vol
     :volume-type "CONFIGFILE"
     :configs (cond-> [(config-entry step-config-file (step-details->edn rt))]
                log-config (conj (config-entry "logback.xml" log-config)))}))

(defn- add-sidecar-env [sc rt]
  (let [wd (calc-work-dir rt)]
    ;; TODO Put this all in a config file instead, this way sensitive information is harder to see
    (assoc sc :environment-variables (-> (rt/rt->env rt)
                                         (dissoc :args :jwk :containers :storage) ;; Remove some unnecessary values
                                         (assoc :work-dir wd)
                                         (assoc-in [:build :checkout-dir] wd)
                                         (c/config->env)
                                         (as-> x (mc/map-keys (comp csk/->SCREAMING_SNAKE_CASE name) x))))))

(defn instance-config
  "Generates the configuration for the container instance.  It has 
   a container that runs the job, as configured in the `:step`, and
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
               :display-name (display-name rt)
               :tags (oci/sid->tags (get-in rt [:build :sid])))
        (update :volumes conj
                (script-vol-config rt)
                (config-vol-config rt)))))

(defmethod mcc/run-container :oci [rt]
  (let [conf (:containers rt)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))
        ic (instance-config conf rt)
        has-job-name? (comp (partial = job-container-name) :display-name)]
    (-> (oci/run-instance client ic {:match-container (partial filter has-job-name?)
                                     :delete? true})
        (md/chain (partial hash-map :exit))
        (deref))))

(defmethod mcc/normalize-containers-config :oci [conf]
  (oci/normalize-config conf :containers))
