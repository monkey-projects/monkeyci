(ns monkey.ci.runtime.app
  "Functions for setting up a runtime for application (cli or server)"
  (:require [com.stuartsierra.component :as co]
            [manifold.bus :as mb]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [metrics :as m]
             [oci :as oci]
             [reporting :as rep]
             [storage :as s]
             [vault :as v]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.db :as emd]
            [monkey.ci.runners.oci :as ro]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.storage.sql]  ; Required for multimethods
            [monkey.ci.web.handler :as wh]
            [monkey.oci.container-instance.core :as ci]))

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

(defn- new-metrics []
  (m/make-metrics))

(defn new-mailman
  "Creates new mailman event broker component.  This will replace the old events."
  [config]
  (em/make-component (:mailman config)))

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
   :http      (co/using
               (new-http-server config)
               {:rt :runtime})
   :runner    (co/using
               (new-server-runner config)
               [:storage :vault :mailman])
   :runtime   (co/using
               (new-server-runtime config)
               [:artifacts :metrics :storage :jwk :process-reaper :vault :mailman :update-bus])
   :storage   (co/using
               (new-storage config)
               [:vault])
   :jwk       (new-jwk config)
   :metrics   (new-metrics)
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
