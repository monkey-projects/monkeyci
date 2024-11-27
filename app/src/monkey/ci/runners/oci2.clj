(ns monkey.ci.runners.oci2
  "Variation of the oci runner, that creates a container instance with two containers:
   one running the controller process, that starts the build api, and the other that
   actually runs the build script.  This is more secure and less error-prone than
   starting a child process."
  (:require [clojure.java.io :as io]
            [monkey.ci
             [build :as b]
             [edn :as edn]
             [oci :as oci]
             [process :as proc]
             [version :as v]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.config.script :as cos]
            [monkey.ci.runners.oci :as ro]))

;; Necessary to be able to write to the shared volume
(def root-user {:security-context-type "LINUX"
                :run-as-user 0})

(def default-container
  {:security-context root-user})

(def config-vol "config")
(def config-path (str oci/home-dir "/config"))
(def config-file "config.edn")
(def log-config-file ro/log-config-file)
(def script-path oci/script-dir)
(def script-vol "script")
(def build-script "build.sh")

(def config-mount
  {:mount-path config-path
   :volume-name config-vol
   :is-read-only true})

(def script-mount
  {:mount-path script-path
   :volume-name script-vol
   :is-read-only true})

(defn- log-config
  "Generates config entry holding the log config, if provided."
  [config]
  (when-let [c (:log-config config)]
    (oci/config-entry log-config-file c)))

(defn- config-volume [config]
  (let [conf (edn/->edn config)]
    (oci/make-config-vol
     config-vol
     (->> [(oci/config-entry config-file conf)
           (log-config config)]
          (remove nil?)))))

(defn- script-config [{:keys [runner] :as config}]
  (-> cos/empty-config
      (cos/set-build (:build config))
      (cos/set-api {:url (format "http://localhost:%d" (:api-port runner))
                    :token (:api-token runner)})))

(defn- generate-deps [{:keys [build] :as config}]
  {:paths [(b/calc-script-dir oci/work-dir (b/script-dir build))]
   :aliases
   {:monkeyci/build
    {:exec-fn 'monkey.ci.process/run
     :extra-deps {'com.monkeyci/app (v/version)}
     :exec-args {:config (script-config config)}}}
   ;;:mvn/local-repo m2-cache-dir
   })

(defn- script-volume
  "Creates a volume that holds necessary files to run the build script"
  [config]
  (oci/make-config-vol
   script-vol
   [(oci/config-entry "deps.edn"
                      (-> (generate-deps config)
                          (pr-str)))
    (oci/config-entry build-script
                      (-> (io/resource "build.sh")
                          (slurp)))]))

(defn controller-container [config]
  (-> default-container
      (assoc :display-name "controller"
             :arguments ["controller"])
      (update :volume-mounts conj config-mount)))

(defn script-container [config]
  ;; TODO Use clojure base image instead of the one from monkeyci
  (-> default-container
      (assoc :display-name "build"
             ;; Run script that waits for run file to be created
             :arguments ["bash" "-c" (str script-path "/" build-script)]
             ;; Tell clojure cli where to find deps.edn
             :environment-variables
             {"CLJ_CONFIG" script-path
              "MONKEYCI_WORK_DIR" oci/work-dir
              "MONKEYCI_START_FILE" (:run-path config)
              "MONKEYCI_ABORT_FILE" (:abort-path config)})
      (update :volume-mounts conj script-mount)))

(defn- make-containers [[orig] config]
  ;; Use the original container but modify it where necessary
  [(merge orig (controller-container config))
   (merge orig (script-container config))])

(defn instance-config
  "Prepares container instance configuration to run a build.  It contains two
   containers, one for the controller process and another one for the script
   itself.  The controller is responsible for preparing the workspace and 
   starting an API server, which the script will connect to."
  [config build]
  (let [file-path (fn [ext]
                    (str oci/checkout-dir "/" (b/build-id build) ext))
        ctx (assoc config
                   :build (-> build
                              (dissoc :ssh-keys :cleanup? :status)
                              (assoc-in [:git :dir] oci/work-dir))
                   :runner {:type :noop
                            :api-port 3000
                            :api-token (bas/generate-token)}
                   :run-path (file-path ".run")
                   :abort-path (file-path ".abort"))]
    (-> (oci/instance-config config)      
        (assoc :display-name (b/build-id build))
        (update :containers make-containers ctx)
        (update :volumes concat [(config-volume ctx)
                                 (script-volume ctx)]))))
