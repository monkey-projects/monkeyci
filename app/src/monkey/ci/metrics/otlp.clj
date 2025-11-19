(ns monkey.ci.metrics.otlp
  "OpenTelemetry client implementation, used to push metrics to an OTLP 
   collector endpoint (e.g. Scaleway cockpit)."
  (:require [monkey.ci.version :as v])
  (:import [io.prometheus.metrics.exporter.opentelemetry OpenTelemetryExporter]))

(defn- add-labels [builder lbls]
  (reduce-kv (fn [b k v]
               (.resourceAttribute b k v))
             builder
             lbls))

(defn make-client
  "Creates a new OTLP client that pushes to given url, using metrics from the
   specified Prometheus registry.  The client automatically pushes metrics
   data at configured intervals (by default 60 seconds)."
  [url reg {:keys [token interval service labels]}]
  (cond-> (.. (OpenTelemetryExporter/builder)
              (endpoint url)
              (registry reg)
              (protocol "http/protobuf")
              (serviceVersion (v/version)))
    token (.header "X-TOKEN" token)
    interval (.intervalSeconds interval)
    service (.serviceName service)
    labels (add-labels labels)
    true (.buildAndStart)))
