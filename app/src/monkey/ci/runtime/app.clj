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
            [monkey.ci.events.mailman
             [db :as emd]
             [interceptors :as emi]
             [jms :as emj]]
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
  "Creates new mailman event broker component."
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
      (if (#{:oci} (:type rc))
        ;; FIXME Compartment id seems to be nil here
        (oci/delete-stale-instances (ci/make-context rc) (get-in rc [:containers :compartment-id]))
        []))))

(defn- new-process-reaper [conf]
  (->ProcessReaper conf))

(defn new-app-routes [conf]
  (letfn [(make-routes [{:keys [storage update-bus]}]
            (emd/make-routes storage update-bus))]
    (em/map->RouteComponent {:make-routes make-routes
                             ;; Make sure to read from queues, not topics to avoid duplicate
                             ;; processing when multiple replicas
                             :destinations (emj/queue-destinations (:mailman conf))})))

(defn new-update-routes []
  (letfn [(make-routes [c]
            [[:build/updated [{:handler (constantly nil)
                               :interceptors [(emi/update-bus (:update-bus c))]}]]])]
    (em/map->RouteComponent {:make-routes make-routes})))

(defmulti make-server-runner (comp :type :runner))

(defmethod make-server-runner :oci [config]
  (letfn [(make-routes [c]
            (ro/make-routes (:runner config)
                            (:vault c)))]
    (em/map->RouteComponent {:make-routes make-routes
                             :destinations (emj/queue-destinations (:mailman config))})))

;; TODO Add other runners

(defmethod make-server-runner :default [_]
  {})

(defn- new-server-runner [config]
  (make-server-runner config))

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
   :mailman   (new-mailman config)
   :mailman-routes (co/using
                    (new-app-routes config)
                    [:mailman :storage])
   :update-routes (co/using
                   (new-update-routes)
                   [:mailman :update-bus])
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
