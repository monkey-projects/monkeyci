(ns monkey.ci.prometheus
  "Functionality to export metrics to prometheus format, or push them to a pushgateway.
   Originally we used `micrometer-clj`, but it's really old and incompatible with recent
   versions of the Prometheus libs.  Since we're fixed on Prometheus (for now), it's not
   necessary to maintain all the other formats, so the micrometer layer was essentially
   ballast.  This namespace accesses the Prometheus code directly."
  (:import [io.prometheus.metrics.core.metrics Counter Gauge]
           [io.prometheus.metrics.model.registry PrometheusRegistry]
           [io.prometheus.metrics.exporter.pushgateway PushGateway]
           [io.prometheus.metrics.expositionformats PrometheusTextFormatWriter]
           [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]))

(defn make-registry
  "Creates a new prometheus registry"
  []
  (PrometheusRegistry.))

(defn ^String scrape
  "Returns metrics formatted to Prometheus scrape format as a string."
  [reg]
  (let [snapshots (.scrape reg)
        writer (PrometheusTextFormatWriter. false)]
    (with-open [os (ByteArrayOutputStream.)]
      (.write writer os snapshots)
      (String. (.toByteArray os) StandardCharsets/UTF_8))))

(defn- ->arr [strs]
  (into-array String strs))

(defn- build-datapoint [dp name reg {:keys [description labels]}]
  (cond-> (.name dp name)
    description (.help description)
    labels (.labelNames (->arr labels))
    true (.register reg)))

(defn make-gauge [name reg & [opts]]
  (build-datapoint (Gauge/builder) name reg opts))

(defn gauge-set [g v]
  (.set g (double v))
  g)

(defn gauge-inc [g & [n]]
  (.inc g (or (double n) 1.0))
  g)

(defn gauge-dec [g & [n]]
  (.dec g (or (double n) 1.0))
  g)

(defn gauge-get [g]
  (.get g))

(defn make-counter [name reg & [opts]]
  (build-datapoint (Counter/builder) name reg opts))

(defn counter-inc [c v & [label-vals]]
  (if label-vals
    (.. c (labelValues (->arr label-vals)) (inc (double v)))
    (.. c (inc (double v))))
  c)

(defn counter-get [c & [label-vals]]
  (if label-vals
    (.. c (labelValues (->arr label-vals)) (get))
    (.get c)))

(defn push-gw
  "Creates a PushGateway object to push metrics to"
  [host port reg job]
  (.. (PushGateway/builder)
      (address (str host ":" port))
      (registry reg)
      (job job)))

(defn push
  "Pushes all registered datapoints to the push gateway"
  [gw]
  (.push gw))
