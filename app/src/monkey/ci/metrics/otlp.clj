(ns monkey.ci.metrics.otlp
  "OpenTelemetry client implementation, used to push metrics to an OTLP 
   collector endpoint (e.g. Scaleway cockpit)."
  (:require [monkey.ci.version :as v]
            [monkey.metrics.otlp :as mmo]))

(defn make-client
  "Creates a new OTLP client that pushes to given url, using metrics from the
   specified Prometheus registry.  The client automatically pushes metrics
   data at configured intervals (by default 60 seconds)."
  [url reg {:keys [token] :as opts}]
  (mmo/make-client url reg
                   (-> opts
                       (dissoc :token)
                       (assoc :headers {"X-TOKEN" token}
                              :version (v/version)))))
