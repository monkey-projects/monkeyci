(ns monkey.ci.runtime.app
  "Functions for setting up a runtime for application (cli or server)"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.bus :as mb]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [containers :as c]
             [git :as git]
             [logging :as l]
             [metrics :as m]
             [oci :as oci]
             [prometheus :as prom]
             [protocols :as p]
             [reporting :as rep]
             [runners :as r]
             [storage :as s]
             [utils :as u]
             [vault :as v]
             [workspace :as ws]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.containers
             [podman :as ccp]
             [oci :as cco]]
            [monkey.ci.events
             [core :as ec]
             [mailman :as em]
             [split :as es]]
            [monkey.ci.events.mailman
             [bridge :as emb]
             [db :as emd]]
            [monkey.ci.runners.oci :as ro]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.web
             [auth :as auth]
             [handler :as wh]]
            [monkey.oci.container-instance.core :as ci]))

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

(defn- new-build-cache [config]
  ;; The build cache is separate from the customer cache, because the build cache
  ;; is (mostly) out of control of the user, where the customer cache is fully
  ;; determined by the user's cache configurations.
  (blob/make-blob-store config :build-cache))

(defn- new-workspace [config]
  (blob/make-blob-store config :workspace))

(defrecord BuildRunner [runner runtime build]
  clojure.lang.IFn
  (invoke [this]
    (runner build runtime)))

(defn- new-build-runner [config]
  (map->BuildRunner {:runner (r/make-runner config)}))

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

(defn new-api-config [{:keys [runner]}]
  {:token (or (:api-token runner)
              (bas/generate-token))
   :port (or (:api-port runner)
             (random-port))})

(defn- new-metrics []
  (m/make-metrics))

(defrecord PushGateway [config metrics]
  co/Lifecycle
  (start [this]
    (cond-> this
      (not-empty config) (assoc :gw (prom/push-gw (:host config)
                                                  (:port config)
                                                  (:registry metrics)
                                                  "monkeyci_build"))))

  (stop [this]
    (when-let [gw (:gw this)]
      (prom/push gw))
    (dissoc this :gw)))

(defn new-push-gw [config]
  (map->PushGateway {:config (:push-gw config)}))

(defn new-mailman
  "Creates new mailman event broker component.  This will replace the old events."
  [config]
  (em/make-component (:mailman config)))

(defn new-local-mailman
  "Creates local mailman component for runners.  This is used to handle events 
   between the build api, the script and container jobs."
  []
  ;; TODO Routes
  (em/make-component {:type :manifold}))

(defn new-mailman-events
  "Creates a mailman-events bridge for compatibility purposes"
  []
  (emb/->MailmanEventPoster nil))

(defn new-event-bus []
  (mb/event-bus))

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
                [:events :artifacts :cache :containers :workspace :logging :git :build :api-config :build-cache])
   :build      (prepare-build config)
   ;; TODO Replace with mailman
   :events     (new-events config)
   :event-bus  (new-event-bus)
   :artifacts  (new-artifacts config)
   :cache      (new-cache config)
   :build-cache (new-build-cache config)
   :workspace  (new-workspace config)
   :containers (co/using
                (new-container-runner config)
                [:events :build :api-config :logging])
   :logging    (new-logging config)
   ;; Runner is only needed when using this runtime for local builds
   :runner     (co/using
                (new-build-runner config)
                [:build :runtime])
   :git        (new-git)
   :api-config (new-api-config config) ; Necessary to avoid circular dependency between containers and api server
   :api-server (co/using
                (new-api-server config)
                [:events :artifacts :cache :containers :workspace :build :params :api-config :event-bus])
   :params     (co/using
                (new-params config)
                [:build])
   :metrics    (new-metrics)
   :push-gw    (co/using
                (new-push-gw config)
                [:metrics])
   :mailman    (new-mailman config)
   :mailman/local (new-local-mailman)))

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
  ;; Return a map because component doesn't allow nils
  (select-keys conf [:jwk]))

(defn- new-vault [config]
  (v/make-vault (:vault config)))

(defrecord ServerRuntime [config]
  co/Lifecycle
  (start [this]
    (assoc this
           :jwk (get-in this [:jwk :jwk])
           :metrics (get-in this [:metrics :registry])))

  (stop [this]
    this))

(defn- new-server-runtime [conf]
  (->ServerRuntime conf))

(defrecord ProcessReaper [config]
  clojure.lang.IFn
  (invoke [this]
    (let [rc (:runner config)]
      (if (#{:oci :oci2} (:type rc))
        (oci/delete-stale-instances (ci/make-context rc) (:compartment-id rc))
        []))))

(defn- new-process-reaper [conf]
  (->ProcessReaper conf))

(defrecord AppEventRoutes [storage update-bus]
  co/Lifecycle
  (start [this]
    (assoc this :routes (emd/make-routes storage update-bus)))

  (stop [this]
    (dissoc this :routes)))

(defn new-app-routes []
  (map->AppEventRoutes {}))

(defmulti make-server-runner :type)

(defmethod make-server-runner :oci [config]
  (ro/map->OciRunner {:config config}))

;; To be removed, provided for compatibility
(defmethod make-server-runner :oci3 [config]
  (ro/map->OciRunner {:config config}))

;; TODO Add other runners

(defmethod make-server-runner :default [_]
  {})

(defn- new-server-runner [config]
  (make-server-runner (:runner config)))

(defn make-server-system
  "Creates a component system that can be used to start an application server."
  [config]
  (co/system-map
   :artifacts (new-artifacts config)
   :events    (co/using
               (new-mailman-events)
               {:broker :mailman})
   :http      (co/using
               (new-http-server config)
               {:rt :runtime})
   :runner    (co/using
               (new-server-runner config)
               [:storage :vault :mailman])
   :runtime   (co/using
               (new-server-runtime config)
               [:artifacts :events :metrics :storage :jwk :process-reaper :vault :mailman :update-bus])
   :storage   (co/using
               (new-storage config)
               [:vault])
   :jwk       (new-jwk config)
   :metrics   (co/using
               (new-metrics)
               [:events])
   :process-reaper (new-process-reaper config)
   :vault     (new-vault config)
   :mailman   (co/using
               (new-mailman config)
               {:routes :mailman-routes})
   :mailman-routes (co/using
                    (new-app-routes)
                    [:storage :update-bus])
   :update-bus (mb/event-bus)))

(defn with-server-system [config f]
  (rc/with-system (make-server-system config) f))

(defn- new-cli-build [conf]
  (b/make-build-ctx conf))

(defn make-cli-system
  "Creates a component system that can be used by CLI commands"
  [config]
  (co/system-map
   :runtime (co/using
             {:config config}
             [:build :reporter])
   :reporter (new-reporter config)
   :build (new-cli-build config)))

(defn with-cli-runtime [config f]
  (rc/with-runtime (make-cli-system config) f))
