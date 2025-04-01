(ns monkey.ci.containers.k8s
  "Kubernetes implementation to run container jobs."
  (:require [monkey.ci
             [build :as b]
             [jobs :as j]]
            [monkey.ci.containers :as co]
            [monkey.ci.containers
             [common :as c]
             [promtail :as pt]]))

(def checkout-mount
  {:name c/checkout-vol
   :mount-point c/checkout-dir})

(defn- pod-name [{:keys [build job]}]
  (str (b/build-id build) "-" (j/job-id job)))

(defn- job-container [{:keys [job]}]
  {:name c/job-container-name
   :restart-policy "Never"
   :image (co/image job)
   :volume-mounts
   [{:name c/script-vol
     :mount-point c/script-dir}
    checkout-mount]})

(defn- sidecar-container [{sc :sidecar :as conf}]
  {:name c/sidecar-container-name
   :image (str (:image-url sc) ":" (:image-tag sc))
   :command (co/make-cmd "internal" "sidecar")
   :volume-mounts
   [{:name c/config-vol
     :mount-point c/config-dir}
    checkout-mount]})

(defn- promtail-container [conf]
  (let [pti (pt/promtail-container (:promtail conf))]
    {:name (:display-name pti)
     :image (:image-url pti)
     :volume-mounts
     [{:name c/promtail-config-vol
       :mount-point c/promtail-config-dir}
      checkout-mount]
     :resources
     {:requests
      {:cpu "25m"
       :memory "100M"}}}))

(defn prepare-pod-config
  "Prepares a list of create actions that can be used to run a container job."
  [conf]
  ;; TODO Create configmaps or secrets as well
  [{:kind :Job
    :action :create
    :request
    {:namespace (:ns conf)
     :body
     {:api-version "batch/v1"
      :metadata
      {:name (pod-name conf)
       :labels {}}
      :spec
      {:backoff-limit 1
       :template
       {:spec
        {:containers
         [(job-container conf)
          (sidecar-container conf)
          (promtail-container conf)]
         :volumes
         [{:name c/checkout-vol
           :empty-dir {}}
          ;; TODO Refer to created configmaps
          {:name c/config-vol
           :config-map {}}
          {:name c/promtail-config-vol
           :config-map {}}]}}}}}}])

;; TODO Event handlers
;; TODO Create pods for jobs, similar to oci container instances
