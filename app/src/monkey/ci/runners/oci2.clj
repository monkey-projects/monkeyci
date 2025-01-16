(ns monkey.ci.runners.oci2
  "Variation of the oci runner, that creates a container instance with two containers:
   one running the controller process, that starts the build api, and the other that
   actually runs the build script.  This is more secure and less error-prone than
   starting a child process."
  (:require [clojure.java.io :as io]
            [medley.core :as mc]
            [meta-merge.core :as mm]
            [monkey.ci
             [build :as b]
             [edn :as edn]
             [oci :as oci]
             [process :as proc]
             [runners :as r]
             [utils :as u]
             [version :as v]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.config.script :as cos]
            [monkey.ci.runners.oci :as ro]
            [monkey.oci.container-instance.core :as ci]))

;; Necessary to be able to write to the shared volume
(def root-user {:security-context-type "LINUX"
                :run-as-user 0})

(def default-container
  {:security-context root-user})

(def build-container-name "build")
(def config-vol "config")
(def config-path (str oci/home-dir "/config"))
(def config-file "config.edn")
(def log-config-file ro/log-config-file)
(def log-config-vol "log-config")
(def script-path oci/script-dir)
(def script-vol "script")
(def build-script "build.sh")
(def m2-cache-dir (str oci/checkout-dir "/m2"))

(def config-mount
  {:mount-path config-path
   :volume-name config-vol
   :is-read-only true})

(def script-mount
  {:mount-path script-path
   :volume-name script-vol
   :is-read-only true})

(def log-config-mount
  {:mount-path config-path
   :volume-name log-config-vol
   :is-read-only true})

(defn- log-config
  "Generates config entry holding the log config, if provided."
  [config]
  (when-let [c (:log-config config)]
    (oci/config-entry log-config-file c)))

(defn- build->out [build]
  (-> build
      (dissoc :status :cleanup?)
      (update :git dissoc :ssh-keys)))

(defn- config-volume [config build rt]
  (let [conf (-> config
                 (ro/add-api-token build rt)
                 (update :build build->out)
                 (ro/add-ssh-keys-dir (:build config))
                 (assoc :m2-cache-path m2-cache-dir)
                 (edn/->edn))]
    (oci/make-config-vol
     config-vol
     (->> [(oci/config-entry config-file conf)
           (log-config config)]
          (remove nil?)))))

(defn- log-config-volume [config]
  (when-let [lc (log-config config)]
    (oci/make-config-vol
     log-config-vol
     [lc])))

(defn- script-config [{:keys [runner] :as config}]
  (-> cos/empty-config
      (cos/set-build (-> (:build config)
                         ;; Credit multiplier for action jobs
                         (assoc :credit-multiplier (oci/credit-multiplier
                                                    oci/default-arch
                                                    oci/default-cpu-count
                                                    oci/default-memory-gb))
                         (build->out)))
      (cos/set-api {:url (format "http://localhost:%d" (:api-port runner))
                    :token (:api-token runner)})))

(defn- generate-deps [{:keys [build lib-version] :as config}]
  {:paths [(b/calc-script-dir oci/work-dir (b/script-dir build))]
   :aliases
   {:monkeyci/build
    (cond-> {:exec-fn 'monkey.ci.process/run
             :extra-deps {'com.monkeyci/app {:mvn/version (or lib-version (v/version))}}
             :exec-args {:config (script-config config)}}
      (:log-config config) (assoc :jvm-opts
                                  [(str "-Dlogback.configurationFile=" config-path "/" log-config-file)]))}
   :mvn/local-repo m2-cache-dir})

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
             :arguments ["-c" (str config-path "/" config-file) "controller"]
             :volume-mounts [config-mount])
      (ro/add-ssh-keys-mount (:build config))))

(defn- checkout-dir [build]
  (u/combine oci/work-dir (b/build-id build)))

(defn script-container [config]
  (-> default-container
      ;; Use configured image instead of the one from monkeyci (probably a clojure image)
      (mc/assoc-some :image-url (:build-image-url config))
      (assoc :display-name build-container-name
             ;; Run script that waits for run file to be created
             :command ["bash" (str script-path "/" build-script)]
             ;; Tell clojure cli where to find deps.edn
             :environment-variables
             ;; TODO Replace CLJ_CONFIG with command-line argument to avoid messing up clj invocations in the script
             {"CLJ_CONFIG" script-path
              "MONKEYCI_WORK_DIR" (b/calc-script-dir (checkout-dir (:build config)) nil)
              "MONKEYCI_START_FILE" (:run-path config)
              "MONKEYCI_ABORT_FILE" (:abort-path config)
              "MONKEYCI_EXIT_FILE" (:exit-path config)}
             :volume-mounts (cond-> [script-mount]
                              (some? (:log-config config)) (conj log-config-mount)))))

(defn- make-containers [[orig] config]
  ;; Use the original container but modify it where necessary
  [(mm/meta-merge orig (controller-container config))
   (mm/meta-merge orig (script-container config))])

(defn instance-config
  "Prepares container instance configuration to run a build.  It contains two
   containers, one for the controller process and another one for the script
   itself.  The controller is responsible for preparing the workspace and 
   starting an API server, which the script will connect to."
  [config build rt]
  (let [bid (b/build-id build)
        file-path (fn [ext]
                    (u/combine oci/checkout-dir (str bid ext)))
        wd (checkout-dir build)
        ctx (assoc config
                   :build (-> build
                              (dissoc :ssh-keys :cleanup? :status)
                              (assoc-in [:git :dir] wd)
                              (assoc :checkout-dir wd))
                   :checkout-base-dir oci/work-dir
                   :runner {:type :noop
                            :api-port 3000
                            :api-token (bas/generate-token)}
                   :run-path (file-path ".run")
                   :abort-path (file-path ".abort")
                   :exit-path (file-path ".exit"))]
    (-> (oci/instance-config config)      
        (assoc :display-name bid)
        (update :containers make-containers ctx)
        (update :volumes concat (->> [(config-volume ctx build rt)
                                      (script-volume ctx)
                                      (log-config-volume ctx)]
                                     (remove nil?)))
        (ro/add-ssh-keys-volume build))))

(defn- oci-runner [client conf build rt]
  (ro/run-oci-build
   (instance-config conf build rt)
   {:client client
    :build build
    :rt rt
    :conf conf
    :find-container (partial filter (comp (partial = build-container-name)
                                          :display-name))}))

(defmethod r/make-runner :oci2 [config]
  (let [runner-conf (:runner config)
        client (-> (ci/make-context runner-conf)
                   (oci/add-inv-interceptor :runners))]
    (partial oci-runner client runner-conf)))
