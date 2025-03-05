(ns monkey.ci.runtime.sidecar
  "Functions for creating a runtime for a build-aware environment for the sidecar"
  (:require [com.stuartsierra.component :as co]
            [monkey.ci
             [artifacts :as art]
             [blob :as blob]
             [cache :as cache]
             [logging :as l]
             [workspace :as ws]]
            [monkey.ci.build.api :as api]
            [monkey.ci.config.sidecar :as cs]
            [monkey.ci.events.mailman.build-api :as eba]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.spec :as spec]
            [monkey.ci.spec.sidecar :as ss]))

(defrecord SidecarRuntime [mailman log-maker workspace artifacts cache]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    (let [props (juxt cs/job cs/build cs/poll-interval)
          paths (juxt cs/events-file cs/start-file cs/abort-file)]
      (-> this
          (merge (zipmap [:job :build :poll-interval] (props config)))
          (assoc :paths (zipmap [:events-file :start-file :abort-file] (paths config))))))
  
  (stop [this]
    this))

(defn- new-runtime [config]
  (map->SidecarRuntime {:config config}))

(defn- new-api-client [config]
  (let [{:keys [url token]} (cs/api config)]
    (api/make-client url token)))

(defn- new-mailman []
  (eba/map->BuildApiBrokerComponent {}))

(defn- new-log-maker [config]
  (l/make-logger {:logging (cs/log-maker config)}))

(defn- new-workspace [config]
  (ws/make-build-api-workspace nil (cs/build config)))

(defn- new-artifacts []
  (art/make-build-api-repository nil))

(defn- new-cache []
  (cache/make-build-api-repository nil))

(defn make-system
  "Given a sidecar configuration object, creates component system.  When started,
   it contains a fully configured `runtime` component."
  [config]
  {:pre [(spec/valid? ::ss/config config)]}
  (co/system-map
   :runtime    (co/using
                (new-runtime config)
                [:mailman :log-maker :workspace :artifacts :cache])
   :api-client (new-api-client config)
   :mailman    (co/using
                (new-mailman)
                [:api-client])
   :log-maker  (new-log-maker config)
   :workspace  (co/using
                (new-workspace config)
                {:client :api-client})
   :artifacts  (co/using
                (new-artifacts)
                {:client :api-client})
   :cache      (co/using
                (new-cache)
                {:client :api-client})))

(defn with-runtime
  "Creates component system according to config, starts it and then 
   passes the `runtime` component from the started system to `f`.  When
   complete, shuts down the system."
  [config f]
  (rc/with-runtime (make-system config) f))

(def runtime? (partial instance? SidecarRuntime))
