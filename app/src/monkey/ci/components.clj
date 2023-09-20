(ns monkey.ci.components
  "Defines components for system startup and integration"
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [commands :as co]
             [events :as e]]
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

(defrecord CommandHandler [bus]
  c/Lifecycle
  (start [this]
    ;; Set up a pipeline that handles events through the command tx,
    ;; then sends the results back to the bus
    (assoc this :handler (e/register-pipeline bus :command/invoked co/command-tx)))

  (stop [this]
    (call-and-dissoc this :handler (partial e/unregister-handler bus))))

(defn new-command-handler []
  (map->CommandHandler {}))

(defrecord HttpServer [bus config]
  c/Lifecycle
  (start [this]
    (assoc this :server (web/start-server (assoc config :bus bus))))

  (stop [this]
    (call-and-dissoc this :server web/stop-server)))

(defn new-http-server []
  (map->HttpServer {}))
