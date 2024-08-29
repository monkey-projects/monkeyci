(ns monkey.ci.runtime.sidecar
  "Functions for creating a runtime for a build-aware environment for the sidecar"
  (:require [com.stuartsierra.component :as co]
            [monkey.ci.config.sidecar :as cs]
            [monkey.ci.events.core :as ec]))

(defrecord SidecarRuntime [events log-maker workspace artifacts cache]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    ;; TODO Add properties from config
    this)
  (stop [this]
    this))

(defn- new-runtime [config]
  (map->SidecarRuntime {:config config}))

(defn- new-events [config]
  (ec/make-events (cs/events config)))

(defn make-system
  "Given a sidecar configuration object, creates component system.  When started,
   it contains a fully configured `runtime` component."
  [config]
  ;; TODO
  (co/system-map
   :runtime (co/using
             (new-runtime config)
             [:events :log-maker :workspace :artifacts :cache])
   :events (new-events config)))

(defn with-runtime
  "Creates component system according to config, starts it and then 
   passes the `runtime` component from the started system to `f`.  When
   complete, shuts down the system."
  [config f]
  (let [sys (-> (make-system config)
                (co/start))]
    (try
      (f (:runtime sys))
      (finally
        (co/stop sys)))))

(def runtime? (partial satisfies? SidecarRuntime))
