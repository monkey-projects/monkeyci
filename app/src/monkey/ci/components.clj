(ns monkey.ci.components
  "Defines components for system startup and integration"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as c]
            [monkey.ci.events :as e]))

(defn- stop-and-dissoc [c key f]
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
