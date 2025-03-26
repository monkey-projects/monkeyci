(ns monkey.ci.dispatcher.runtime
  "Runtime components for executing the dispatcher"
  (:require [com.stuartsierra.component :as co]
            [monkey.ci.web.http :as http]))

;; Should connect to the JMS message broker
;; Should provide a http endpoint for metrics and health checks
;; Should provide a deferred that can be waited upon for termination
;; Should register a shutdown hook

(defn new-http-server [conf]
  (http/->HttpServer conf nil))

(defrecord HttpApp []
  co/Lifecycle
  (start [this]
    ;; TODO
    (assoc this :handler (constantly {:status 404})))

  (stop [this]
    this))

(defn new-http-app []
  (map->HttpApp {}))

(defn make-system [conf]
  ;; TODO
  (co/system-map
   :http-server (co/using
                 (new-http-server (:http conf))
                 {:app :http-app})
   :http-app {}))
