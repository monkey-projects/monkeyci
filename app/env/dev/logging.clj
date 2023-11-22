(ns logging
  (:require [common :as c]
            [config :as co]
            [monkey.ci.logging :as l]
            [monkey.oci.os.core :as os]))

(defn test-stream-to-bucket []
  (let [c (co/load-config "oci-config.edn")
        m (l/make-logger (:logging c))
        in (java.io.ByteArrayInputStream. (.getBytes "Hi, this is a test string"))
        logger (m c ["test.log"])]
    @(l/handle-stream logger in)))

(defn get-build-logs
  "Downloads logs for a given build from log bucket"
  [sid]
  (let [client (os/make-client (co/oci-config :logging))]))
