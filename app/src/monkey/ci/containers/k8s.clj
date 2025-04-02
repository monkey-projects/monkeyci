(ns monkey.ci.containers.k8s
  "Kubernetes implementation to run container jobs."
  (:require [clojure.java.io :as io]
            [kubernetes-api.core :as k8s-api]
            [monkey.ci
             [build :as b]
             [edn :as edn]
             [jobs :as j]]
            [monkey.ci.containers :as co]
            [monkey.ci.containers
             [common :as c]
             [promtail :as pt]]
            [monkey.ci.events.mailman.interceptors :as emi]))

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

(defn- select-node-arch
  "When `arch` is specified, adds a node selector to make sure the pod is run on a
   node with that architecture."
  [spec arch]
  (letfn [(get-arch-lbl [arch]
            (str (name arch) "64"))]
    (cond-> spec
      arch (assoc-in [:node-selector "kubernetes.io/arch"] (get-arch-lbl arch)))))

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
          (-> {:containers
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
                 :config-map {:name pt-config-name}}]}
              (select-node-arch (get-in conf [:job :arch])))}}}})]))

;;; Context management

(def get-k8s-actions ::k8s-actions)

(defn set-k8s-actions [ctx a]
  (assoc ctx ::k8s-actions a))

(def get-k8s-results ::k8s-results)

(defn set-k8s-results [ctx a]
  (assoc ctx ::k8s-results a))

(def get-build ::build)

(defn set-build [ctx b]
  (assoc ctx ::build b))

;;; Interceptors

(defn run-k8s-actions [client]
  "Executes k8s actions stored in the context"
  {:name ::run-k8s-actions
   :leave (fn [ctx]
            (->> (get-k8s-actions ctx)
                 (map (partial k8s-api/invoke client))
                 (doall)
                 (set-k8s-results ctx)))})

(defn add-build [build]
  {:name ::add-build
   :enter (fn [ctx]
            (set-build ctx build))})

;;; Event handlers

(defn job-queued [ctx]
  (let [job (get-in ctx [:event :job])
        build (get-build ctx)]
    ;; TODO Build k8s config
    (set-k8s-actions ctx (prepare-pod-config {:job job
                                              :build build}))))

;;; Event routing

;; Very similar to oci jobs

(defn make-routes [{:keys [build] :as conf}]
  (let [state (emi/with-state (atom {}))]
    [[:k8s/job-queued
      [{:handler job-queued
        :interceptors [state
                       (add-build build)
                       (run-k8s-actions (get-in conf [:k8s :client]))]}]]
     
     [:container/start []]
     [:container/end []]
     [:sidecar/end []]
     [:job/executed []]]))
