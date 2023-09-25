(ns monkey.ci.components
  "Defines components for system startup and integration"
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [commands :as co]
             [events :as e]
             [process :as p]
             [runners :as r]]
            [monkey.ci.web.handler :as web]))

(defn- call-and-dissoc [c key f]
  (when-let [x (key c)]
    (f x))
  (dissoc c key))

(defrecord BusComponent []
  c/Lifecycle
  (start [this]
    (log/debug "Creating event bus")
    (merge this (e/make-bus)))
  
  (stop [this]
    (log/debug "Stopping event bus")
    (e/close-bus this)
    (dissoc this :pub :channel)))

(defn new-bus []
  (->BusComponent))

(defrecord HttpServer [bus config]
  c/Lifecycle
  (start [this]
    (assoc this :server (web/start-server (assoc config :bus bus))))

  (stop [this]
    (call-and-dissoc this :server web/stop-server)))

(defn new-http-server []
  (map->HttpServer {}))

(defn- register-tx-runners [bus]
  (->> {:build/started (comp (map r/build-local)
                             (remove nil?))
        :build/completed (map r/build-completed)}
       (mapv (partial apply e/register-pipeline bus))))

(defn- register-fn-handlers [bus]
  [(e/register-handler bus :build/local p/execute!)])

(defrecord BuildRunners [config bus]
  c/Lifecycle
  (start [this]
    (log/debug "Registering build runner handlers")
    (assoc this :handlers (concat (register-tx-runners bus)
                                  (register-fn-handlers bus))))

  (stop [this]
    (call-and-dissoc this :handlers (partial map (partial e/unregister-handler bus)))))

(defn new-build-runners []
  (map->BuildRunners {}))

(defrecord Context [command config event-bus]
  c/Lifecycle
  (start [this]
    (-> this
        (merge config)
        (dissoc :config)))
  (stop [this]
    this))

(defn new-context [cmd]
  (map->Context {:command cmd}))
