(ns monkey.ci.containers.oci
  "Container runner implementation that uses OCI container instances."
  (:require [monkey.ci.containers :as mcc]
            [monkey.oci.container-instance.core :as ci]))

(defmethod mcc/run-container :oci [ctx]
  ;; TODO
  ;; - (Make sure the exctracted code, caches and artifacts are uploaded to storage)
  ;; - Build the config struct that includes the sidecar
  ;; - Use common volume for logs and events
  ;; - Create a container instance
  ;; - Wait for it to finish
  ;; - Clean up temp files from storage
  {:exit 0})
