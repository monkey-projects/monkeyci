(ns monkey.ci.runners.runtime
  "Creates a runtime component that is used by the controller."
  (:require [clojure.set :as cs]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [blob :as blob]
             [git :as git]
             [prometheus :as prom]
             [protocols :as p]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.containers.oci :as c-oci]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman
             [interceptors :as emi]
             [jms :as emj]]
            [monkey.ci.metrics.core :as m]
            [monkey.ci.runtime.common :as rc]
            [monkey.mailman.core :as mmc]))

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
  "Creates new mailman event broker component that connects to the global
   event broker."
  [config]
  (em/make-component (:mailman config)))

(defn new-local-mailman
  "Creates local mailman component for runners.  This is used to handle events 
   between the build api, the script and container jobs."
  []
  (em/make-component {:type :manifold}))

(defrecord EventForwarder [mailman make-handlers listeners]
  co/Lifecycle
  (start [this]
    (assoc this :listeners (->> (make-handlers this)
                                (map (partial mmc/add-listener (:broker mailman)))
                                (doall))))

  (stop [{:keys [listeners] :as this}]
    (doseq [l listeners]
      (mmc/unregister-listener l))
    (assoc this :listeners nil)))

(def global-to-local-events
  #{:build/canceled})

(def local-to-global-events
  "Set of events that should be forwarded to global broker"
  (cs/difference
   (->> emj/destination-types
        (vals)
        (flatten)
        (set))
   global-to-local-events))

(defn- broker-forwarder [evts-to-fwd dest]
  (fn [evt]
    (when (evts-to-fwd (:type evt))
      (log/debug "Forwarding event:" evt)
      (when (empty? (em/post-events dest [evt]))
        (log/warn "Unable to forward event:" evt)))
    nil))

(defn- to-event-stream [stream evt]
  (-> (ms/put! stream evt)
      (md/chain
       (fn [r]
         (if r
           (log/debug "Event pushed to internal stream")
           (log/warn "Failed to push event to internal stream")))))
  nil)

(defn new-local-to-global-forwarder
  "Adds listeners responsible for pushing all events generated by the build to the 
   upstream broker and the event stream."
  []
  (map->EventForwarder {:make-handlers (fn [{:keys [global-mailman event-stream]}]
                                         [(broker-forwarder local-to-global-events global-mailman)
                                          (partial to-event-stream event-stream)])}))

(defn global-to-local-routes
  "Creates new event handler routes, that handle events received from the global broker."
  []
  (em/map->RouteComponent
   {:make-routes (fn [{:keys [local-mailman]}]
                   [[:build/canceled [{:handler (constantly nil)
                                       :interceptors [(emi/forwarder
                                                       ::forward-to-local
                                                       local-mailman)]}]]])}))

(defmulti make-container-routes (comp :type :containers))

(defmethod make-container-routes :oci [conf]
  (log/debug "Creating OCI container routes for build" (:build conf))
  (c-oci/make-routes (-> conf
                         (dissoc :containers)
                         (assoc :oci (:containers conf)
                                :api (bas/srv->api-config (:api conf))))
                     (:build conf)))

(defn new-container-routes
  "Creates new event handler routes that handle events raised by the controller and 
   script processes for running container jobs." 
  [conf]
  (letfn [(make-routes [c]
            (-> (merge c (select-keys conf [:containers :promtail]))
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
   :global-mailman (new-mailman config)
   :routes     (co/using
                (global-to-local-routes)
                {:mailman :global-mailman
                 :local-mailman :mailman})
   :mailman (new-local-mailman)
   :container-routes (co/using
                      (new-container-routes config)
                      {:mailman :mailman
                       :build :build
                       :api :api-config})
   :global-forwarder (co/using
                      (new-local-to-global-forwarder)
                      [:global-mailman :mailman :event-stream])))

(defn with-runner-system [config f]
  (rc/with-system (make-runner-system config) f))
