(ns monkey.ci.local.runtime
  "Set up runtime for local builds"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as a]
             [blob :as blob]
             [cache :as c]
             [protocols :as p]]
            [monkey.ci.containers.mailman :as cm]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.bridge :as emb]
            [monkey.ci.local
             [config :as lc]
             [events :as le]]
            [monkey.ci.runtime
             [app :as ra]
             [common :as rc]]
            [monkey.mailman.core :as mmc]))

(defn- new-mailman []
  (em/make-component {:type :manifold}))

(defrecord Routes [routes mailman api-config]
  co/Lifecycle
  (start [this]
    (let [routes (-> this
                     :config
                     (lc/set-api api-config)
                     (le/make-routes mailman))]
      (assoc this
             :routes routes
             :listener (em/add-router mailman routes {:interceptors em/global-interceptors}))))

  (stop [{:keys [listener] :as this}]
    (when listener
      (mmc/unregister-listener listener))
    (dissoc this :listener)))

(defn- new-routes [conf]
  (map->Routes {:config conf}))

(defn- new-events []
  (emb/->MailmanEventPoster nil))

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

(defn make-system
  "Creates a component system that can be used for local builds"
  [conf]
  (co/system-map
   :build      (lc/get-build conf)
   :mailman    ;; Can specify custom event broker, for testing
               (or (:mailman conf) (new-mailman))
   :routes     (co/using
                (new-routes conf)
                [:mailman :api-config])
   :events     (co/using
                (new-events)
                {:broker :mailman})
   :artifacts  (new-artifacts conf)
   :cache      (new-cache conf)
   :params     (new-params conf)
   :containers (co/using
                (new-containers)
                [:mailman :build])
   :api-config (ra/new-api-config {})
   :api-server (co/using
                (new-api-server)
                [:api-config :events :artifacts :cache :params :containers :build])))

(defn start-and-post
  "Starts component system and posts an event to the event broker to trigger
   the action flow.
   Returns a deferred that will realize when the build ends, which can be used
   to wait upon."
  [conf evt]
  (let [result (md/deferred)]
    (rc/with-system-async (make-system (assoc conf :result result))
      (fn [sys]
        (log/debug "System started, posting event:" evt)
        (em/post-events (:mailman sys) [evt])
        result))))
