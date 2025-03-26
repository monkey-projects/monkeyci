(ns monkey.ci.dispatcher.runtime
  "Runtime components for executing the dispatcher"
  (:require [com.stuartsierra.component :as co]
            [monkey.ci.dispatcher.http :as dh]
            [monkey.ci.metrics.core :as metrics]
            [monkey.ci.web.http :as http]))

;; Should connect to the JMS message broker
;; Should register a shutdown hook

(defn new-http-server [conf]
  (http/->HttpServer conf nil))

(defrecord HttpApp []
  co/Lifecycle
  (start [this]
    (assoc this :handler (dh/make-handler this)))

  (stop [this]
    this))

(defn new-http-app []
  (map->HttpApp {}))

(defn new-metrics []
  (metrics/make-registry))

(defn make-system [conf]
  (co/system-map
   :http-server (co/using
                 (new-http-server (:http conf))
                 {:app :http-app})
   :http-app (co/using
              (new-http-app)
              [:metrics])
   :metrics (new-metrics)))
