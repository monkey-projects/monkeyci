(ns monkey.ci.containers.oci
  "Container runner implementation that uses OCI container instances."
  (:require [monkey.ci.containers :as mcc]
            [monkey.oci.container-instance.core :as ci]))

(defn instance-config [conf ctx]
  ;; TODO
  {})

(defmethod mcc/run-container :oci [ctx]
  ;; TODO
  ;; - (Make sure the exctracted code, caches and artifacts are uploaded to storage)
  ;; - Generate and upload script files to execute
  ;; - Build the config struct that includes the sidecar
  ;; - Use common volume for logs and events
  ;; - Create a container instance
  ;; - Wait for it to finish
  ;; - Clean up temp files from storage
  {:exit 0})
