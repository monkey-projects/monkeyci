(ns monkey.ci.runners.oci
  "Another implementation of a job runner that uses OCI container instances.
   This one uses mailman-style events instead of manifold.  This should make
   it more robust and better suited for multiple replicas.  Instead of waiting
   for a container instance to complete, we just register multiple event 
   handlers that follow the flow."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [io.pedestal.interceptor.chain :as pi]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [meta-merge.core :as mm]
            [monkey.ci
             [build :as b]
             [edn :as edn]
             [errors :as errors]
             [oci :as oci]
             [protocols :as p]
             [storage :as st]
             [utils :as u]
             [version :as v]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.config.script :as cos]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.web.auth :as auth]
            [monkey.mailman
             [core :as mmc]
             [interceptors :as mmi]]
            [monkey.oci.container-instance.core :as ci]))

;; Necessary to be able to write to the shared volume
(def root-user {:security-context-type "LINUX"
                :run-as-user 0})

(def default-container
  {:security-context root-user})

(def build-container-name "build")
(def build-ssh-keys (comp :ssh-keys :git))
(def config-vol "config")
(def config-path (str oci/home-dir "/config"))
(def config-file "config.edn")
(def log-config-file "logback.xml")
(def log-config-vol "log-config")
(def script-path oci/script-dir)
(def script-vol "script")
(def build-script "build.sh")
(def m2-cache-dir (str oci/checkout-dir "/m2"))
(def ssh-keys-dir oci/key-dir)
(def ssh-keys-volume "ssh-keys")

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

(defn- add-api-token
  "Generates a new API token that can be used by the build runner to invoke
   certain API calls."
  [conf build]
  (assoc-in conf [:api :token] (auth/generate-and-sign-jwt
                                (auth/build-token (b/sid build))
                                (get-in conf [:api :private-key]))))

(defn- add-ssh-keys-dir [conf build]
  (cond-> conf
    (build-ssh-keys build) (assoc-in [:build :git :ssh-keys-dir] ssh-keys-dir)))

(defn- ssh-keys-volume-config [build]
  (letfn [(->config-entries [idx ssh-keys]
            (map (fn [contents ext]
                   (oci/config-entry (format "key-%d%s" idx ext) contents))
                 ((juxt :private-key :public-key) ssh-keys)
                 ["" ".pub"]))]
    (when-let [ssh-keys (build-ssh-keys build)]
      (oci/make-config-vol ssh-keys-volume
                           (mapcat ->config-entries (range) ssh-keys)))))

(defn add-ssh-keys-volume [conf build]
  (let [vc (ssh-keys-volume-config build)]
    (cond-> conf
      vc (update :volumes conj vc))))

(def ssh-keys-mount
  {:mount-path ssh-keys-dir
   :is-read-only true
   :volume-name ssh-keys-volume})

(defn add-ssh-keys-mount [conf build]
  (cond-> conf
    (build-ssh-keys build) (update :volume-mounts conj ssh-keys-mount)))

(defn- config-volume [config build]
  (let [conf (-> config
                 (add-api-token build)
                 (update :build build->out)
                 (add-ssh-keys-dir (:build config))
                 (assoc :m2-cache-path m2-cache-dir)
                 ;; Remove stuff we don't need
                 (dissoc :private-key :jwk)
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

(defn- checkout-dir [build]
  (u/combine oci/work-dir (b/build-id build)))

(defn- script-dir [build]
  (b/calc-script-dir (checkout-dir build) (b/script-dir build)))

(defn- generate-deps [{:keys [build lib-version] :as config}]
  {:paths [(script-dir build)]
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
      (add-ssh-keys-mount (:build config))))

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
              "MONKEYCI_WORK_DIR" (script-dir (:build config))
              "MONKEYCI_START_FILE" (:run-path config)
              "MONKEYCI_ABORT_FILE" (:abort-path config)
              "MONKEYCI_EXIT_FILE" (:exit-path config)}
             :volume-mounts (cond-> [script-mount]
                              (some? (:log-config config)) (conj log-config-mount)))))

(defn- make-containers [[orig] config]
  ;; Use the original container but modify it where necessary
  [(mm/meta-merge orig (controller-container config))
   (mm/meta-merge orig (script-container config))])

(defn- set-script-dir [build]
  ;; Recalculates script dir for container
  (b/set-script-dir build (script-dir build)))

(defn instance-config
  "Prepares container instance configuration to run a build.  It contains two
   containers, one for the controller process and another one for the script
   itself.  The controller is responsible for preparing the workspace and 
   starting an API server, which the script will connect to."
  [config build]
  (let [bid (b/build-id build)
        file-path (fn [ext]
                    (u/combine oci/checkout-dir (str bid ext)))
        wd (checkout-dir build)
        ctx (assoc config
                   :build (-> build
                              (dissoc :ssh-keys :cleanup? :status)
                              (assoc-in [:git :dir] wd)
                              (assoc :checkout-dir wd)
                              (set-script-dir))
                   :checkout-base-dir oci/work-dir
                   :runner {:type :noop
                            :api-port 3000
                            :api-token (bas/generate-token)}
                   :run-path (file-path ".run")
                   :abort-path (file-path ".abort")
                   :exit-path (file-path ".exit"))]
    (-> (oci/instance-config (:containers config))      
        (assoc :display-name bid)
        (update :containers make-containers ctx)
        (update :volumes concat (->> [(config-volume ctx build)
                                      (script-volume ctx)
                                      (log-config-volume ctx)]
                                     (remove nil?)))
        (add-ssh-keys-volume build))))

(def get-ci-config ::ci-config)

(defn set-ci-config [ctx bi]
  (assoc ctx ::ci-config bi))

(def get-ci-response ::ci-response)

(defn set-ci-response [ctx bi]
  (assoc ctx ::ci-response bi))

(def get-runner-details ::runner-details)

(defn set-runner-details [ctx bi]
  (assoc ctx ::runner-details bi))

(def evt-build (comp :build :event))

(defn decrypt-ssh-keys
  "Interceptor that decrypts ssh keys on incoming build event"
  [vault]
  (letfn [(decrypt-keys [get-iv ssh-keys]
            (let [iv (get-iv)]
              (mapv #(update % :private-key (partial p/decrypt vault iv)) ssh-keys)))]
    {:name ::decrypt-ssh-keys
     :enter (fn [ctx]
              (update-in ctx [:event :build :git]
                         mc/update-existing
                         :ssh-keys
                         (partial decrypt-keys
                                  (comp :iv #(st/find-crypto (em/get-db ctx) (-> ctx :event :sid first))))))}))

(defn prepare-ci-config [config]
  "Creates the ci config to run the required containers for the build."
  {:name ::instance-config
   :enter (fn [ctx]
            (set-ci-config ctx (instance-config config (evt-build ctx))))})

(defn- create-instance [client config]
  (log/debug "Creating container instance using config" config)
  (oci/with-retry
    #(ci/create-container-instance client {:container-instance config})))

(defn start-ci [client]
  "Interceptor that starts container instance using the config specified in the context"
  {:name ::start-ci
   :enter (fn [ctx]
            ;; Start container instance, put result back in the context
            ;; TODO Async processing?
            (->> @(create-instance client (get-ci-config ctx))
                 (set-ci-response ctx)))})

(def end-on-ci-failure
  {:name ::end-on-ci-failure
   :enter (fn [ctx]
            (let [resp (get-ci-response ctx)
                  build (evt-build ctx)]
              (cond-> ctx
                (>= (:status resp) 400)
                (-> (assoc :result (b/build-end-evt
                                    (assoc build :message "Failed to create container instance")))
                    ;; Do not proceed
                    (pi/terminate)))))})

(def save-runner-details
  "Interceptor that stores build runner details for oci, such as container instance ocid.
   This assumes the db is present in the context."
  {:name ::save-runner-details
   :enter (fn [ctx]
            (let [sid (get-in ctx [:event :sid])
                  details {:runner :oci
                           :details {:instance-id (get-in (get-ci-response ctx) [:body :id])}}]
              (log/debug "Saving runner details:" details)
              (st/save-runner-details (em/get-db ctx) sid details)
              ctx))})

(def load-runner-details
  "Interceptor that fetches build runner details from the db.
   This assumes the db is present in the context."
  {:name ::load-runner-details
   :enter (fn [ctx]
            (set-runner-details ctx (st/find-runner-details (em/get-db ctx) (get-in ctx [:event :sid]))))})

(def handle-error
  "Marks build as failed"
  {:name ::error-handler
   :error (fn [{:keys [event] :as ctx} ex]
            (log/error "Failed to handle event" (:type event) ", marking build as failed" ex)
            (-> ctx
                (em/set-result (b/build-end-evt (-> (:build event)
                                                    (assoc :message (ex-message ex)))
                                                errors/error-process-failure))
                ;; Remove the error to ensure leave chain is processed
                (dissoc ::pi/error)))})

(defn initialize-build [ctx]
  (b/build-init-evt (get-in ctx [:event :build])))

(defn delete-instance
  "Deletes the container instance associated with the build"
  [client ctx]
  (if-let [instance-id (-> ctx (get-runner-details) :details :instance-id)]
    @(md/chain
      (oci/with-retry #(ci/delete-container-instance client {:instance-id instance-id}))
      (fn [res]
        (if (< (:status res) 400)
          (log/info "Container instance" instance-id "has been deleted")
          (log/warn "Unable to delete container instance" instance-id ", got status" (:status res)))))
    (log/warn "Unable to delete container instance, no instance id in context")))

(defn- make-ci-context [conf]
  (-> (ci/make-context conf)
      (oci/add-inv-interceptor :runners)))

(defn make-routes
  "Creates event handling routes for the given oci configuration"
  [conf vault]
  (let [client (make-ci-context (:containers conf))]
    ;; TODO Timeout handling
    [[:build/queued
      [{:handler initialize-build
        :interceptors [(decrypt-ssh-keys vault)
                       (prepare-ci-config conf)
                       (start-ci client)
                       save-runner-details
                       end-on-ci-failure]}]]

     [:build/end
      [{:handler (partial delete-instance client)
        :interceptors [load-runner-details]}]]]))

(defn- make-interceptors [storage]
  [em/trace-evt
   (em/use-db storage)
   (mmi/sanitize-result)
   handle-error])

(defn make-router [conf storage vault]
  (mmc/router (make-routes conf vault)
              {:interceptors (make-interceptors storage)}))

;; This component is used by the app runtime to enable oci runner
(defrecord OciRunner [storage mailman vault]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    (-> this
        (assoc :listeners (em/add-router mailman
                                         (make-routes config vault)
                                         {:interceptors (make-interceptors storage)}))
        (dissoc :config)))

  (stop [{:keys [listeners] :as this}]
    (doseq [l listeners]
      (mmc/unregister-listener l))
    (dissoc this :listeners)))
