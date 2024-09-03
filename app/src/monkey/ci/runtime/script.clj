(ns monkey.ci.runtime.script
  "Functions for creating a runtime for build scripts"
  (:require [com.stuartsierra.component :as co]
            [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [runtime :as rt]
             [spec :as spec]]
            [monkey.ci.build.api :as api]
            [monkey.ci.config.script :as cs]
            [monkey.ci.events.build-api :as eba]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.spec.script :as ss]))

(defrecord ScriptRuntime [events artifacts cache build]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    (assoc this :build (cs/build config)))
  
  (stop [this]
    this))

(def runtime? (partial instance? ScriptRuntime))

(defn- new-runtime [config]
  (map->ScriptRuntime {:config config}))

(defn- new-api-client [config]
  (let [{:keys [url token]} (cs/api config)]
    (api/make-client url token)))

(defn- new-events []
  (eba/make-event-poster nil))

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
                [:events :artifacts :cache])
   :api-client (new-api-client config)
   :events     (co/using
                (new-events)
                {:client :api-client})
   :artifacts  (co/using
                (new-artifacts)
                {:client :api-client})
   :cache      (co/using
                (new-cache)
                {:client :api-client})))

(defn with-runtime [config f]
  (rc/with-runtime (make-system config) f))
