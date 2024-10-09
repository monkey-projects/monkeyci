(ns monkey.ci.runtime.app
  "Functions for setting up a runtime for application (cli or server)"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [containers :as c]
             [git :as git]
             [listeners :as li]
             [logging :as l]
             [metrics :as m]
             [protocols :as p]
             [reporting :as rep]
             [runners :as r]
             [storage :as s]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.containers
             [podman :as ccp]
             [oci :as cco]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.web
             [auth :as auth]
             [handler :as wh]]))

(defrecord AppRuntime [config events artifacts cache containers workspace logging git build api-config])

(def runtime? (partial instance? AppRuntime))

(defn- new-runtime [config]
  (map->AppRuntime {:config config}))

(defn- new-events [config]
  (ec/make-events (:events config)))

(defn- new-artifacts [config]
  (blob/make-blob-store config :artifacts))

(defn- new-cache [config]
  (blob/make-blob-store config :cache))

(defn- new-workspace [config]
  (blob/make-blob-store config :workspace))

(defrecord BuildRunner [runner runtime build]
  clojure.lang.IFn
  (invoke [this]
    (runner build runtime)))

(defn- new-build-runner [config]
  (map->BuildRunner {:runner (r/make-runner config)}))

(defn- new-server-runner [config]
  (r/make-runner config))

(defn- make-container-runner [{:keys [containers] :as config} events build api-config logging]
  (case (:type containers)
    :podman (ccp/make-container-runner
             (-> config
                 (select-keys [:dev-mode])
                 (assoc :logging logging
                        :events events
                        :build build)))
    :oci    (cco/make-container-runner
             (-> config
                 (select-keys [:promtail :sidecar :api])
                 (assoc :oci containers
                        :build build
                        :api (bas/srv->api-config api-config)))
             events)))

(defrecord DelayedContainerRunner [config events build api-config logging]
  co/Lifecycle
  (start [this]
    (assoc this :target-runner (make-container-runner config events build api-config logging)))
  (stop [this]
    this)
  p/ContainerRunner
  (run-container [this job]
    (p/run-container (:target-runner this) job)))

(defn- new-container-runner [conf]
  ;; Since not all build details are known at this point, we can't initialize a
  ;; plain container runner.  Instead, we initialize a wrapper, that accepts the
  ;; build as an argument.
  (map->DelayedContainerRunner {:config conf}))

(defn- new-logging [config]
  {:maker (l/make-logger config)})

(defn- new-git []
  {:clone (fn default-git-clone [opts]
            (git/clone+checkout opts)
            ;; Return the checkout dir
            (:dir opts))})

(defn- prepare-build [{:keys [build] :as config}]
  (-> build
      ;; Checkout paths for git
      (assoc :workspace (ws/workspace-dest build)
             :checkout-dir (some->
                            (:checkout-base-dir config)
                            (u/combine (:build-id build))))))

(defrecord ApiBuildParams [api-config build]
  p/BuildParams
  (get-build-params [this]
    (bas/get-params-from-api api-config build)))

(defn new-params [config]
  (->ApiBuildParams (:api config) nil))

(defrecord ApiServer [build api-config]
  co/Lifecycle
  (start [this]
    (-> this
        (merge (bas/start-server (merge this (select-keys api-config [:port :token]))))
        (dissoc :config)))

  (stop [this]
    (when-let [srv (:server this)]
      (log/debug "Shutting down API server")
      (.close srv))))

(defn new-api-server [config]
  (map->ApiServer {:config config}))

(defn- random-port []
  (+ (rand-int 10000) 30000))

(defn new-api-config [config]
  {:token (bas/generate-token)
   :port (or (get-in config [:runner :api-port])
             (random-port))})

(defn make-runner-system
  "Given a runner configuration object, creates component system.  When started,
   it contains a fully configured `runtime` component that can be used by the
   local runner.  It's still up to the runner to execute all steps required for
   the build, typically those that can go wrong, like checking out the source 
   from git, etc..."
  [config]
  (co/system-map
   :runtime    (co/using
                (new-runtime config)
                [:events :artifacts :cache :containers :workspace :logging :git :build :api-config])
   :build      (prepare-build config)
   :events     (new-events config)
   :artifacts  (new-artifacts config)
   :cache      (new-cache config)
   :workspace  (new-workspace config)
   :containers (co/using
                (new-container-runner config)
                [:events :build :api-config :logging])
   :logging    (new-logging config)
   :runner     (co/using
                (new-build-runner config)
                [:build :runtime])
   :git        (new-git)
   :api-config (new-api-config config) ; Necessary to avoid circular dependency between containers and api server
   :api-server (co/using
                (new-api-server config)
                [:events :artifacts :cache :containers :workspace :build :params :api-config])
   :params     (co/using
                (new-params config)
                [:build])))

(defn with-runner-system [config f]
  (rc/with-system (make-runner-system config) f))

(defn- new-storage [config]
  (if (not-empty (:storage config))
    (s/make-storage config)
    (s/make-memory-storage)))

(defn- new-http-server [_]
  (wh/->HttpServer nil))

(defn- new-reporter [conf]
  (rep/make-reporter (:reporter conf)))

(defn- new-jwk [conf]
  ;; Wrapped in a map because component doesn't allow nils
  {:jwk (auth/config->keypair conf)})

(defn- new-listeners []
  (li/map->Listeners {}))

(defrecord ServerRuntime [config]
  co/Lifecycle
  (start [this]
    (assoc this :jwk (get-in this [:jwk :jwk])))

  (stop [this]
    this))

(defn- new-server-runtime [conf]
  (->ServerRuntime conf))

(defn- new-metrics []
  (m/make-registry))

(defn make-server-system
  "Creates a component system that can be used to start an application server."
  [config]
  (co/system-map
   :artifacts (new-artifacts config)
   :events    (new-events config)
   :http      (co/using
               (new-http-server config)
               {:rt :runtime})
   :reporter  (new-reporter config)
   :runner    (new-server-runner config)
   :runtime   (co/using
               (new-server-runtime config)
               [:artifacts :events :metrics :reporter :runner :storage :jwk])
   :storage   (new-storage config)
   :jwk       (new-jwk config)
   :listeners (co/using
               (new-listeners)
               [:events :storage])
   :metrics   (co/using
               (new-metrics)
               [:events])))

(defn with-server-system [config f]
  (rc/with-system (make-server-system config) f))
