(ns monkey.ci.metrics.otlp
  "OpenTelemetry client implementation, used to push metrics to an OTLP 
   collector endpoint (e.g. Scaleway cockpit)."
  (:import [io.prometheus.metrics.exporter.opentelemetry OpenTelemetryExporter]))

(defn make-client
  "Creates a new OTLP client that pushes to given url, using metrics from the
   specified Prometheus registry.  The client automatically pushes metrics
   data at configured intervals (by default 60 seconds)."
  [url reg {:keys [token interval service]}]
  (cond-> (.. (OpenTelemetryExporter/builder)
              (endpoint url)
              (registry reg)
              (protocol "http/protobuf"))
    token (.header "X-TOKEN" token)
    interval (.intervalSeconds interval)
    service (.serviceName service)
    true (.buildAndStart)))
