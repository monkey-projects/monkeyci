(ns monkey.ci.local.runtime
  "Set up runtime for local builds"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [artifacts :as a]
             [blob :as blob]
             [cache :as c]
             [protocols :as p]]
            [monkey.ci.containers
             [mailman :as cm]
             [podman :as cp]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.bridge :as emb]
            [monkey.ci.local
             [config :as lc]
             [events :as le]]
            [monkey.ci.runtime
             [app :as ra]
             [common :as rc]]
            [monkey.mailman.core :as mmc]))

(defn- new-mailman
  "Creates a new internal event broker.  This broker is intended to dispatch events
   between the controller, the script and container job sidecars, so it's only 
   in-memory."
  []
  (em/make-component {:type :manifold}))

(defn- new-routes [conf]
  (letfn [(make-routes [c]
            (-> (:config c)
                (lc/set-api (:api-config c))
                (le/make-routes (:mailman c))))]
    (em/map->RouteComponent {:config conf :make-routes make-routes})))

(defn- new-podman-routes [conf]
  (letfn [(make-routes [{:keys [config] :as c}]
            (-> (select-keys c [:build :mailman :artifacts :cache])
                (assoc :workspace (lc/get-workspace config)
                       :work-dir (lc/get-jobs-dir config))
                (cp/make-routes)))]
    (em/map->RouteComponent {:config conf :make-routes make-routes})))

(defn- blob-store [dir]
  (blob/->DiskBlobStore (str dir)))

(defn- new-artifacts [conf]
  (blob-store (lc/get-artifact-dir conf)))

(defn- new-cache [conf]
  (blob-store (lc/get-cache-dir conf)))

(defn- new-containers []
  (cm/make-container-runner))

(defrecord FixedBuildParams [params]
  p/BuildParams
  (get-build-params [_]
    (md/success-deferred params)))

(defn- new-params [conf]
  (->FixedBuildParams (lc/get-params conf)))

(defn- new-api-server []
  (ra/new-api-server {}))

(defn new-event-stream
  "Creates a new event stream, that can be used by the api server to send events to the client."
  []
  (ms/stream))

(defrecord EventPipe [mailman event-stream]
  co/Lifecycle
  (start [this]
    (assoc this :listener (mmc/add-listener (:broker mailman)
                                            (fn [evt]
                                              (ms/put! event-stream evt)
                                              nil))))

  (stop [{l :listener :as this}]
    (when l
      (mmc/unregister-listener l))
    (dissoc this :listener)))

(defn new-event-pipe
  "Registers a listener in mailman to pipe all events to the event bus, where
   they will be picked up by the api server to send events to clients using SSE."
  []
  (map->EventPipe {}))

(defn make-system
  "Creates a component system that can be used for local builds"
  [conf]
  (co/system-map
   :build        (lc/get-build conf)
   :mailman      (or (:mailman conf) (new-mailman)) ; Can specify custom event broker, for testing
   ;; TODO "upstream" mailman to push events to global broker and to capture build/canceled events.
   :routes       (co/using
                  (new-routes conf)
                  [:mailman :api-config])
   :podman       (co/using
                  (new-podman-routes conf)
                  [:mailman :build :artifacts :cache])
   :artifacts    (new-artifacts conf)
   :cache        (new-cache conf)
   :params       (new-params conf)
   ;; TODO Remove
   :containers   (co/using
                  (new-containers)
                  [:mailman :build])
   :api-config   (ra/new-api-config {})
   :api-server   (co/using
                  (new-api-server)
                  [:api-config :artifacts :cache :params :containers :build :event-stream :mailman])
   :event-stream (new-event-stream)
   :event-pipe   (co/using
                  (new-event-pipe)
                  [:event-stream :mailman])))

(defn start-and-post
  "Starts component system and posts an event to the event broker to trigger
   the action flow.
   Returns a deferred that will realize when the build ends, which can be used
   to wait upon."
  [conf evt]
  (let [result (md/deferred)]
    ;; Alternatively, we could add a component in the system that posts the event
    (rc/with-system-async (make-system (lc/set-ending conf result))
      (fn [sys]
        (log/debug "System started, posting event:" evt)
        (em/post-events (:mailman sys) [evt])
        result))))
