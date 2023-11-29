(ns monkey.ci.containers.oci
  "Container runner implementation that uses OCI container instances."
  (:require [monkey.ci.containers :as mcc]
            [monkey.oci.container-instance.core :as ci]))

(defmethod mcc/run-container :oci [ctx]
  ;; TODO
  {:exit 0})
