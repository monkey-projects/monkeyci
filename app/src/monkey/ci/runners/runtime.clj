(ns monkey.ci.runners.runtime
  "Creates a runtime component that is used by the controller."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [monkey.ci
             [blob :as blob]
             [git :as git]
             [metrics :as m]
             [prometheus :as prom]
             [protocols :as p]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.containers.oci :as c-oci]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.runtime.common :as rc]))

(defrecord ControllerRuntime [config artifacts cache workspace git build api-config mailman])

(def runtime? (partial instance? ControllerRuntime))

(defn- new-runtime [config]
  (map->ControllerRuntime {:config config}))

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

(defn new-routes
  "Creates new event handler routes, that handle events received from the global broker."
  [conf]
  ;; TODO
  {})

(defmulti make-container-routes (comp :type :containers))

(defmethod make-container-routes :oci [conf]
  (c-oci/make-routes (-> conf
                         (dissoc :containers)
                         (assoc :oci (:containers conf)))
                     (:build conf)))

(defn new-container-routes
  "Creates new event handler routes that handle events raised by the controller and 
   script processes for running container jobs." 
  [conf]
  (letfn [(make-routes [c]
            (-> (merge c (select-keys conf [:containers]))
                (make-container-routes)))]
    (em/map->RouteComponent {:make-routes make-routes})))

(defn new-event-stream []
  (ms/stream))

(defn make-runner-system
  "Given a runner configuration object, creates component system.  When started,
   it contains a fully configured `runtime` component that can be used by the
   controller.  In addition, it creates a build api for the script process and also
   a mailman event broker for dispatching."
  [config]
  (co/system-map
   :runtime    (co/using
                (new-runtime config)
                [:mailman :artifacts :cache :workspace :git :build :api-config :build-cache])
   :build      (prepare-build config)
   :event-stream (new-event-stream)
   :artifacts  (new-artifacts config)
   :cache      (new-cache config)
   :build-cache (new-build-cache config)
   :workspace  (new-workspace config)
   :git        (new-git)
   :api-config (new-api-config config) ; Necessary to avoid circular dependency between routes and api server
   :api-server (co/using
                (new-api-server config)
                [:artifacts :cache :workspace :build :params :api-config :event-stream :mailman])
   :params     (co/using
                (new-params config)
                [:build])
   :metrics    (new-metrics)
   :push-gw    (co/using
                (new-push-gw config)
                [:metrics])
   :mailman    (new-mailman config)
   :routes     (co/using
                (new-routes config)
                [:mailman])
   :local/mailman (new-local-mailman)
   :container-routes (co/using
                      (new-container-routes config)
                      {:mailman :local/mailman
                       :build :build
                       :api :api-config})))

(defn with-runner-system [config f]
  (rc/with-system (make-runner-system config) f))
