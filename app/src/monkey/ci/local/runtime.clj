(ns monkey.ci.local.runtime
  "Set up runtime for local builds.  These are builds where the api server is run
   in the same process, and the build scripts as child processes (either native or
   as containers)."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [artifacts :as a]
             [build :as b]
             [cache :as c]
             [params :as params]
             [protocols :as p]]
            [monkey.ci.blob.disk :as blob]
            [monkey.ci.containers.podman :as cp]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.local
             [config :as lc]
             [events :as le]
             [print :as lp]]
            [monkey.ci.runners.runtime :as rr]
            [monkey.ci.runtime.common :as rc]
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

(defn- new-print-routes [conf]
  (letfn [(make-routes [_]
            (lp/make-routes {:printer lp/console-printer}))]
    (em/map->RouteComponent {:config conf :make-routes make-routes})))

(defn- new-podman-routes [conf]
  (letfn [(make-routes [{:keys [config build] :as c}]
            (let [sid (b/sid build)]
              (log/debug "Creating local podman routes for build" build)
              (-> (select-keys c [:mailman :artifacts :cache :workspace :key-decrypter])
                  (merge (select-keys config [:podman]))
                  (update :artifacts a/make-blob-repository sid)
                  (update :cache c/make-blob-repository sid)
                  (assoc :work-dir (lc/get-jobs-dir config))
                  (cp/make-routes))))]
    (em/map->RouteComponent {:config conf :make-routes make-routes})))

(defn- blob-store [dir]
  (blob/->DiskBlobStore (str dir)))

(defn- new-artifacts [conf]
  (blob-store (lc/get-artifact-dir conf)))

(defn- new-cache [conf]
  (blob-store (lc/get-cache-dir conf)))

(defn- new-params [conf]
  (params/->FixedBuildParams (lc/get-params conf)))

(defn- new-api-server []
  (rr/new-api-server {}))

(defn new-event-stream
  "Creates a new event stream, that can be used by the api server to send events to the client."
  []
  (ms/stream))

(defrecord EventPipe [mailman event-stream]
  co/Lifecycle
  (start [this]
    (assoc this :listener (mmc/add-listener (:broker mailman)
                                            {:handler
                                             (fn [evt]
                                               (ms/put! event-stream evt)
                                               nil)})))

  (stop [{l :listener :as this}]
    (when l
      (mmc/unregister-listener l))
    (dissoc this :listener)))

(defn new-event-pipe
  "Registers a listener in mailman to pipe all events to the event bus, where
   they will be picked up by the api server to send events to clients using SSE."
  []
  (map->EventPipe {}))

(defrecord CopyStore [src]
  p/BlobStore
  ;; Only suitable for restoring workspaces
  (restore-blob [this _ dest]
    {:src (str src)
     :dest (str (fs/copy-tree src dest))}))

(defn copy-store [conf]
  (->CopyStore (lc/get-workspace conf)))

(defn make-system
  "Creates a component system that can be used for local builds"
  [conf]
  (co/system-map
   :build        (lc/get-build conf)
   :mailman      (or (:mailman conf) (new-mailman)) ; Can specify custom event broker, for testing
   :routes       (co/using
                  (new-routes conf)
                  [:mailman :api-config])
   :print-routes (co/using
                  (new-print-routes conf)
                  [:mailman])
   :podman       (co/using
                  (new-podman-routes conf)
                  [:mailman :artifacts :cache :workspace :build :key-decrypter])
   :artifacts    (new-artifacts conf)
   :cache        (new-cache conf)
   :workspace    (copy-store conf)
   :params       (new-params conf)
   :api-config   (rr/new-api-config {})
   :api-server   (co/using
                  (new-api-server)
                  [:api-config :artifacts :cache :params :build :event-stream :mailman
                   :key-decrypter])
   :event-stream (new-event-stream)
   :event-pipe   (co/using
                  (new-event-pipe)
                  [:event-stream :mailman])
   :key-decrypter (constantly (md/success-deferred (:dek (lc/get-build conf))))))

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
