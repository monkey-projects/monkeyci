(ns monkey.ci.script.runtime
  "Functions for creating a runtime for build scripts"
  (:require [com.stuartsierra.component :as co]
            [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [runtime :as rt]
             [spec :as spec]]
            [monkey.ci.build.api :as api]
            [monkey.ci.script.config :as sc]
            [monkey.ci.containers.build-api :as cba]
            [monkey.ci.events.build-api :as eba]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.spec.script :as ss]))

(defrecord ScriptRuntime [events artifacts cache build containers api-client event-bus]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    (assoc this
           :build (sc/build config)
           :api {:client api-client}))
  
  (stop [this]
    this))

(def runtime? (partial instance? ScriptRuntime))

(defn- new-runtime [config]
  (map->ScriptRuntime {:config config}))

(defn- client-url [{:keys [url port]}]
  (if port
    ;; Script child process always connects to localhost
    (format "http://localhost:%d" port)
    url))

(defn- new-api-client [config]
  (let [{:keys [token] :as ac} (sc/api config)]
    (api/make-client (client-url ac) token)))

(defn- new-events []
  (eba/make-event-poster nil))

(defn- new-artifacts []
  (art/make-build-api-repository nil))

(defn- new-cache []
  (cache/make-build-api-repository nil))

(defn- new-container-runner []
  (cba/map->BuildApiContainerRunner {}))

(defrecord EventBus [client]
  co/Lifecycle
  (start [this]
    (merge this (api/event-bus client)))
  (stop [this]
    (when-let [s (:close this)]
      (s))
    (dissoc this :bus :close)))

(defn- new-event-bus []
  (->EventBus nil))

(defn- using-api [obj]
  (co/using
   obj
   {:client :api-client}))

(defn make-system
  "Given a script configuration object, creates component system.  When started,
   it contains a fully configured `runtime` component that can be passed to the
   script functions."
  [config]
  {:pre [(spec/valid? ::ss/config config)]}
  (co/system-map
   :runtime    (co/using
                (new-runtime config)
                [:events :artifacts :cache :containers :api-client :event-bus])
   :api-client (new-api-client config)
   :events     (using-api (new-events))
   :event-bus  (using-api (new-event-bus))
   :artifacts  (using-api (new-artifacts))
   :cache      (using-api (new-cache))
   :containers (co/using
                (new-container-runner)
                {:client :api-client
                 :bus :event-bus})
   ;; TODO Mailman broker and routes
   ))

(defn with-runtime [config f]
  (rc/with-runtime (make-system config) f))

(def build :build)
