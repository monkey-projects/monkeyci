(ns monkey.ci.prometheus
  "Functionality to export metrics to prometheus format, or push them to a pushgateway.
   Originally we used `micrometer-clj`, but it's really old and incompatible with recent
   versions of the Prometheus libs.  Since we're fixed on Prometheus (for now), it's not
   necessary to maintain all the other formats, so the micrometer layer was essentially
   ballast.  This namespace accesses the Prometheus code directly."
  (:require [medley.core :as mc])
  (:import [io.prometheus.metrics.core.metrics Counter CounterWithCallback Gauge GaugeWithCallback]
           [io.prometheus.metrics.model.registry PrometheusRegistry]
           [io.prometheus.metrics.exporter.pushgateway PushGateway]
           [io.prometheus.metrics.expositionformats PrometheusTextFormatWriter]
           [io.prometheus.metrics.instrumentation.jvm JvmMetrics]
           [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]))

(defn make-registry
  "Creates a new prometheus registry"
  []
  (let [r (PrometheusRegistry.)]
    ;; Add JVM metrics
    (.. (JvmMetrics/builder) (register r))
    r))

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

(defn- build-datapoint [dp name reg {:keys [description labels builder]}]
  (cond-> (.name dp name)
    description (.help description)
    labels (.labelNames (->arr labels))
    builder (builder)
    true (.register reg)))

(defn- as-callback
  "Creates a lambda that can be used as a callback.  It invokes `f`, and if it
   returns a sequence, the first value is assumed to be the data, and the remainder
   the label values."
  [f]
  (reify java.util.function.Consumer
    (accept [this cb]
      (let [r (f)]
        ;; TODO Support multiple values (invokes `call` multiple times)
        (if (sequential? r)
          (.call cb (double (first r)) (->arr (rest r)))
          (.call cb (double r) (make-array String 0)))))))

(defn- prepare-opts [{:keys [callback] :as opts}]
  (-> opts
      (dissoc :callback)
      (mc/assoc-some :builder
                     (when callback
                       (fn [builder]
                         (.callback builder (as-callback callback)))))))

(defn make-gauge [name reg & [opts]]
  (build-datapoint (if (:callback opts)
                     (GaugeWithCallback/builder)
                     (Gauge/builder))
                   name
                   reg
                   (prepare-opts opts)))

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
  (build-datapoint
   (if (:callback opts)
     (CounterWithCallback/builder)
     (Counter/builder))
   name
   reg
   (prepare-opts opts)))

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
