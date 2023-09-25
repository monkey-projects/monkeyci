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
    ;; Alternatively we could just initialize the handler here and
    ;; let commmands/http-server actually start it.
    (assoc this :server (web/start-server (assoc config :bus bus))))

  (stop [this]
    (call-and-dissoc this :server web/stop-server)))

(defn new-http-server []
  (map->HttpServer {}))

(defrecord Context [command config event-bus]
  c/Lifecycle
  (start [this]
    (-> this
        (merge config)
        (dissoc :config)
        (update :runner r/make-runner)))
  (stop [this]
    this))

(defn new-context [cmd]
  (map->Context {:command cmd}))
