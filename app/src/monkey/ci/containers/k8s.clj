(ns monkey.ci.containers.k8s
  "Kubernetes implementation to run container jobs."
  (:require [clojure.java.io :as io]
            [monkey.ci
             [build :as b]
             [edn :as edn]
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

(defn- ->env [m]
  (map (fn [[k v]]
         {:name k
          :value v})
       m))

(defn- job-env [job]
  (->env (co/env job)))

(defn- job-container [{:keys [job build]}]
  (let [wd (c/job-work-dir job build)]
    (cond-> {:name c/job-container-name
             :restart-policy "Never"
             :image (co/image job)
             :command (or (co/cmd job)
                          (co/entrypoint job))
             :arguments (co/args job)
             :env (co/env job)
             :working-dir wd
             :volume-mounts
             [{:name c/script-vol
               :mount-point c/script-dir}
              checkout-mount]
             :resources
             {:requests
              {:cpu (get job :cpus 1)
               :memory (str (get job :memory 2) "G")}}}
      (:script job) (-> (assoc :command [(get job :shell "/bin/sh") (str c/script-dir "/" c/job-script)]
                               ;; One file arg per script line, with index as name
                               :arguments (->> (count (:script job))
                                               (range)
                                               (mapv str)))
                        (update :env
                                merge
                                {"MONKEYCI_WORK_DIR" wd
                                 "MONKEYCI_LOG_DIR" c/log-dir
                                 "MONKEYCI_SCRIPT_DIR" c/script-dir
                                 "MONKEYCI_START_FILE" c/start-file
                                 "MONKEYCI_ABORT_FILE" c/abort-file
                                 "MONKEYCI_EVENT_FILE" c/event-file}))
      true (update :env ->env))))

(defn- sidecar-container [{sc :sidecar :as conf}]
  {:name c/sidecar-container-name
   :image (str (:image-url sc) ":" (:image-tag sc))
   :command c/sidecar-cmd
   :volume-mounts
   [{:name c/config-vol
     :mount-point c/config-dir}
    checkout-mount]
   :resources
   {:requests
    {:cpu "100m"
     :memory "300M"}}})

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

(defn- create-config-map [conf]
  {:kind :ConfigMap
   :action :create
   :request (assoc-in conf [:body :api-version] "core/v1")})

(defn- create-job [conf]
  {:kind :Job
   :action :create
   :request (assoc-in conf [:body :api-version] "batch/v1")})

(defn- job-config-entries [conf]
  (->> (get-in conf [:job :script])
       (map-indexed (fn [idx s]
                      {(str idx) s}))
       (into {c/job-script (slurp (io/resource c/job-script))})))

(defn- pt-config-entries [conf]
  {c/promtail-config-file (-> (pt/make-config (:promtail conf)
                                              (:job conf)
                                              (:build conf))
                              (pt/->yaml))})

(defn- sidecar-config-entries [conf]
  (let [lc (get-in conf [:sidecar :log-config])]
    (cond-> {c/config-file (-> conf
                               (c/make-sidecar-config)
                               (edn/->edn))}
      lc (assoc "logback.xml" lc))))

(defn prepare-pod-config
  "Prepares a list of create actions that can be used to run a container job."
  [conf]
  (let [job-config-name "job-config"
        sidecar-config-name "sidecar-config"
        pt-config-name "promtail-config"]
    [(create-config-map
      {:namespace (:ns conf)
       :body
       {:metadata
        {:name job-config-name}
        :data (job-config-entries conf)}})
     (create-config-map
      {:namespace (:ns conf)
       :body
       {:metadata
        {:name pt-config-name}
        :data (pt-config-entries conf)}})
     (create-config-map
      {:namespace (:ns conf)
       :body
       {:metadata
        {:name sidecar-config-name}
        :data (sidecar-config-entries conf)}})
     (create-job
      {:namespace (:ns conf)
       :body
       {:metadata
        {:name (pod-name conf)
         :labels {}}
        :spec
        ;; TODO Set node affinity using labels according to job arch
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
            {:name c/config-vol
             :config-map {:name sidecar-config-name}}
            {:name c/script-vol
             :config-map {:name job-config-name}}
            {:name c/promtail-config-vol
             :config-map {:name pt-config-name}}]}}}}})]))

;; TODO Interceptors

;; TODO Event handlers

;; TODO Event routing
;; Very similar to oci jobs
