(ns monkey.ci.runners.oci2
  "Variation of the oci runner, that creates a container instance with two containers:
   one running the controller process, that starts the build api, and the other that
   actually runs the build script.  This is more secure and less error-prone than
   starting a child process."
  (:require [monkey.ci
             [build :as b]
             [edn :as edn]
             [oci :as oci]]
            #_[monkey.ci.runners.oci :as ro]))

;; Necessary to be able to write to the shared volume
(def root-user {:security-context-type "LINUX"
                :run-as-user 0})

(def default-container
  {:security-context root-user})

(def config-vol "config")
(def config-path (str oci/home-dir "/config"))
(def config-file "config.edn")

(def config-mount
  {:mount-path config-path
   :volume-name config-vol
   :is-read-only true})

(defn- config-volume [config]
  (let [conf (-> (:config config)
                 (assoc :build (:build config))
                 (edn/->edn))]
    (oci/make-config-vol
     config-vol
     [(oci/config-entry config-file conf)])))

(defn controller-container [config]
  (-> default-container
      (assoc :display-name "controller"
             :arguments ["java"
                         "-Xmx1g"
                         "-Dlogback.configurationFile=config/logback.xml"
                         "-cp" "monkeyci.jar"
                         "monkey.ci.runners.oci2"])
      (update :volume-mounts conj config-mount)))

(defn script-container [config]
  ;; TODO Use clojure base image instead of the one from monkeyci
  (-> default-container
      (assoc :display-name "build"
             ;; TODO Run script that waits for run file to be created
             :arguments ["clojure"])))

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
  (let [run-path (str oci/checkout-dir "/" (b/build-id) ".run")
        ctx (assoc config
                   :build (dissoc build :ssh-keys :cleanup? :status)
                   :run-path run-path)]
    (-> (oci/instance-config config)      
        (assoc :display-name (b/build-id build))
        (update :containers make-containers ctx)
        (update :volumes conj (config-volume ctx)))))

